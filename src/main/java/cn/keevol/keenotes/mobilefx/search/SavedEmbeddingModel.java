package cn.keevol.keenotes.mobilefx.search;

import java.util.Objects;

/** A saved provider/model; enabling semantic search belongs to the catalog selection. */
public record SavedEmbeddingModel(String id, String name, EmbeddingConfig config) {
    public SavedEmbeddingModel {
        if (id == null || !id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid saved model ID");
        Objects.requireNonNull(config);
        name = name == null || name.isBlank() ? config.model() : name.strip();
        config = new EmbeddingConfig(false, config.baseUrl(), config.model(), config.apiKey(), config.documentPrefix(), config.queryPrefix());
    }
    @Override public String toString() { return name; }
}
