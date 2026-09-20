package cn.keevol.keenotes.mobilefx.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only the OpenAI embeddings protocol, with no provider SDK or provider-specific private endpoint. */
public final class OpenAiEmbeddingClient implements EmbeddingClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean closed;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final OkHttpClient queryHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build();

    @Override public List<float[]> embedDocuments(EmbeddingConfig config, List<String> inputs) throws IOException {
        return request(config, inputs.stream().map(s -> config.documentPrefix() + s).toList(), http, new SearchCancellation());
    }

    @Override public float[] embedQuery(EmbeddingConfig config, String query) throws IOException {
        return embedQuery(config, query, new SearchCancellation());
    }

    @Override public float[] embedQuery(EmbeddingConfig config, String query, SearchCancellation cancellation) throws IOException {
        return request(config, List.of(config.queryPrefix() + query), queryHttp, cancellation).getFirst();
    }

    private List<float[]> request(EmbeddingConfig config, List<String> inputs, OkHttpClient transport, SearchCancellation cancellation) throws IOException {
        if (closed) throw new ProviderException("Embedding client is closed");
        if (!config.configured() || inputs.isEmpty() || inputs.stream().anyMatch(String::isBlank))
            throw new ProviderException("Embedding configuration or input is empty");
        Request.Builder request = new Request.Builder().url(config.endpoint().toString())
                .post(RequestBody.create(json.writeValueAsBytes(Map.of("model", config.model(), "input", inputs, "encoding_format", "float")), JSON));
        if (!config.apiKey().isBlank()) request.header("Authorization", "Bearer " + config.apiKey());
        Call call = transport.newCall(request.build());
        cancellation.attach(call::cancel);
        try (Response response = call.execute()) {
            // Provider response bodies may echo note text or credentials. Never include them in errors/logs.
            if (!response.isSuccessful()) throw new ProviderException("Embedding service returned HTTP " + response.code());
            if (response.body() == null) throw new ProviderException("Embedding response is empty");
            JsonNode root;
            try { root = json.readTree(response.body().byteStream()); }
            catch (IOException e) { throw new ProviderException("Embedding response is not valid JSON"); }
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() != inputs.size()) throw new ProviderException("Embedding response count does not match input count");
            float[][] vectors = new float[inputs.size()][];
            int dimensions = -1;
            int maxDimensions = SearchVectorCodec.MAX_DIMENSIONS;
            for (JsonNode item : data) {
                JsonNode index = item.get("index");
                JsonNode values = item.get("embedding");
                if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()) throw new ProviderException("Embedding response is missing a valid index");
                int slot = index.intValue();
                if (slot < 0 || slot >= vectors.length || vectors[slot] != null || values == null || !values.isArray()
                        || values.isEmpty() || values.size() > maxDimensions) throw new ProviderException("Embedding response has invalid indexes or unsupported dimensions");
                if (dimensions != -1 && dimensions != values.size()) throw new ProviderException("Embedding dimensions are inconsistent");
                dimensions = values.size();
                float[] vector = new float[dimensions];
                double norm = 0;
                for (int i = 0; i < dimensions; i++) {
                    if (!values.get(i).isNumber()) throw new ProviderException("Embedding contains a non-numeric value");
                    vector[i] = values.get(i).floatValue();
                    if (!Float.isFinite(vector[i])) throw new ProviderException("Embedding contains a non-finite value");
                    norm += (double) vector[i] * vector[i];
                }
                if (!(norm > 0) || !Double.isFinite(norm)) throw new ProviderException("Embedding has an invalid norm");
                // Scale in double precision to avoid float overflow in Lucene's normalization.
                double length = Math.sqrt(norm);
                for (int i = 0; i < dimensions; i++) vector[i] = (float) (vector[i] / length);
                vectors[slot] = vector;
            }
            cancellation.check();
            return Arrays.asList(vectors);
        } finally { cancellation.detach(); }
    }

    public void cancelRequests() { http.dispatcher().cancelAll(); }
    @Override public void close() {
        closed = true;
        cancelRequests(); http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
    }
    public static final class ProviderException extends IOException {
        public ProviderException(String message) { super(message); }
    }
}
