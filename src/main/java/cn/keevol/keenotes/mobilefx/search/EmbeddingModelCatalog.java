package cn.keevol.keenotes.mobilefx.search;

import java.util.List;

/** Immutable settings view: many saved models, at most one selected for search. */
public record EmbeddingModelCatalog(List<SavedEmbeddingModel> models, String selectedId, boolean enabled) {
    public EmbeddingModelCatalog {
        models = List.copyOf(models);
        selectedId = selectedId == null ? "" : selectedId;
        // Older settings could remember a selection while disabled. Present that as None.
        if (!enabled) selectedId = "";
        if (models.stream().map(SavedEmbeddingModel::id).distinct().count() != models.size())
            throw new IllegalArgumentException("Duplicate saved model ID");
        if (models.stream().map(m -> m.config().profile()).distinct().count() != models.size())
            throw new IllegalArgumentException("This model configuration is already saved");
        String selection = selectedId;
        if (!selection.isEmpty() && models.stream().noneMatch(model -> model.id().equals(selection)))
            throw new IllegalArgumentException("Select a saved model");
        if (enabled && selection.isEmpty()) throw new IllegalArgumentException("Add and select a model before enabling semantic search");
    }
    public SavedEmbeddingModel selected() {
        return models.stream().filter(model -> model.id().equals(selectedId)).findFirst().orElse(null);
    }
    public EmbeddingConfig configuration() {
        SavedEmbeddingModel selected = selected();
        if (selected == null) return EmbeddingConfig.disabled();
        EmbeddingConfig c = selected.config();
        return new EmbeddingConfig(enabled, c.baseUrl(), c.model(), c.apiKey(), c.documentPrefix(), c.queryPrefix());
    }
}
