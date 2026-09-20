package cn.keevol.keenotes.mobilefx.search;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Comparator;

/** One segmenter per TokenStream; dictionary loading and all tokenization happen off the FX thread. */
final class JiebaAnalyzer extends Analyzer {
    private final JiebaSegmenter.SegMode mode;
    JiebaAnalyzer() { this(JiebaSegmenter.SegMode.INDEX); }
    JiebaAnalyzer(JiebaSegmenter.SegMode mode) { this.mode = mode; }
    @Override protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new Tokenizer() {
            private final JiebaSegmenter segmenter = new JiebaSegmenter();
            private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
            private final OffsetAttribute offsets = addAttribute(OffsetAttribute.class);
            private final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
            private Iterator<SegToken> tokens = List.<SegToken>of().iterator();
            private int length;
            private int previousStart;

            @Override public void reset() throws IOException {
                super.reset();
                StringBuilder text = new StringBuilder();
                char[] chars = new char[2048];
                int count;
                while ((count = input.read(chars)) != -1) text.append(chars, 0, count);
                length = text.length();
                // Java jieba's INDEX mode emits subwords before the full word. Lucene requires
                // monotonic offsets; tokens sharing a start occupy the same position.
                tokens = segmenter.process(text.toString(), mode).stream()
                        .sorted(Comparator.comparingInt((SegToken token) -> token.startOffset)
                                .thenComparingInt(token -> token.endOffset)).iterator();
                previousStart = -1;
            }

            @Override public boolean incrementToken() {
                while (tokens.hasNext()) {
                    SegToken token = tokens.next();
                    if (token.word.isBlank()) continue;
                    clearAttributes();
                    term.append(token.word);
                    offsets.setOffset(correctOffset(token.startOffset), correctOffset(token.endOffset));
                    position.setPositionIncrement(token.startOffset == previousStart ? 0 : 1);
                    previousStart = token.startOffset;
                    return true;
                }
                return false;
            }

            @Override public void end() throws IOException {
                super.end();
                offsets.setOffset(correctOffset(length), correctOffset(length));
            }
        };
        return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
    }
}
