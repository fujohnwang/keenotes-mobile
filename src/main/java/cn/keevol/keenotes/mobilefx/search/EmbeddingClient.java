package cn.keevol.keenotes.mobilefx.search;

import java.io.IOException;
import java.util.List;

public interface EmbeddingClient extends AutoCloseable {
    List<float[]> embedDocuments(EmbeddingConfig config, List<String> inputs) throws IOException;
    float[] embedQuery(EmbeddingConfig config, String query) throws IOException;
    default float[] embedQuery(EmbeddingConfig config, String query, SearchCancellation cancellation) throws IOException {
        cancellation.check();
        float[] result = embedQuery(config, query);
        cancellation.check();
        return result;
    }
    @Override default void close() { }

    static EmbeddingClient unavailable() {
        return new EmbeddingClient() {
            public List<float[]> embedDocuments(EmbeddingConfig c, List<String> in) throws IOException {
                throw new IOException("Embedding service is unavailable");
            }
            public float[] embedQuery(EmbeddingConfig c, String in) throws IOException {
                throw new IOException("Embedding service is unavailable");
            }
        };
    }
}
