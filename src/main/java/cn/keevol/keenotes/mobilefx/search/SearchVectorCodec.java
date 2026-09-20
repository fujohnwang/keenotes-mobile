package cn.keevol.keenotes.mobilefx.search;

import org.apache.lucene.codecs.*;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.index.*;

import java.io.IOException;

/** Higher-dimensional models use Lucene's standard on-disk format and standard SPI readers. */
public final class SearchVectorCodec {
    public static final int MAX_DIMENSIONS = 16_384;
    private SearchVectorCodec() { }

    public static IndexWriterConfig configure(IndexWriterConfig config) {
        return config.setCodec(new Lucene104Codec() {
            @Override public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                KnnVectorsFormat delegate = super.getKnnVectorsFormatForField(field);
                return new KnnVectorsFormat(delegate.getName()) {
                    @Override public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
                        return delegate.fieldsWriter(state);
                    }
                    @Override public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
                        return delegate.fieldsReader(state);
                    }
                    @Override public int getMaxDimensions(String name) { return MAX_DIMENSIONS; }
                };
            }
        });
    }
}
