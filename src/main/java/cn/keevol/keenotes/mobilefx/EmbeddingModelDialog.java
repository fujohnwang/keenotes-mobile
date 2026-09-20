package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.search.*;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.UUID;

/** Add/edit stays out of the model overview. Saving never performs IO on the FX thread. */
final class EmbeddingModelDialog extends Dialog<Void> {
    private final TextField name = new TextField();
    private final TextField url = new TextField();
    private final TextField model = new TextField();
    private final PasswordField key = new PasswordField();
    private final TextArea documentPrefix = new TextArea();
    private final TextArea queryPrefix = new TextArea();
    private final Label feedback = SearchSettingsPane.hint("");
    private final Button test = SearchSettingsPane.button("Test connection", false);
    private final String id;
    private Task<Integer> probe;
    private boolean saving;

    EmbeddingModelDialog(Scene owner, LocalSearchService service, SavedEmbeddingModel existing) {
        id = existing == null ? UUID.randomUUID().toString() : existing.id();
        setTitle(existing == null ? "Add embedding model" : "Configure embedding model");
        if (owner != null) {
            if (owner.getWindow() != null) initOwner(owner.getWindow());
            getDialogPane().getStylesheets().setAll(owner.getStylesheets());
        }
        getDialogPane().getStyleClass().addAll("search-settings", "embedding-model-dialog");
        getDialogPane().setId("embedding-model-editor");
        getDialogPane().setPrefWidth(600);
        name.setId("embedding-display-name"); url.setId("embedding-base-url"); model.setId("embedding-model-id"); key.setId("embedding-api-key");
        name.setPromptText("e.g. Local Ollama"); url.setPromptText("http://localhost:11434/v1");
        model.setPromptText("Embedding model ID"); key.setPromptText("Optional for local services");
        if (existing != null) {
            name.setText(existing.name()); url.setText(existing.config().baseUrl()); model.setText(existing.config().model());
            key.setText(existing.config().apiKey()); documentPrefix.setText(existing.config().documentPrefix()); queryPrefix.setText(existing.config().queryPrefix());
        }
        documentPrefix.setPrefRowCount(2); queryPrefix.setPrefRowCount(2);
        VBox advancedFields = new VBox(12, field("Document prefix", documentPrefix), field("Query prefix", queryPrefix),
                SearchSettingsPane.hint("Optional prefixes required by some embedding models."));
        advancedFields.setPadding(new Insets(12));
        TitledPane advanced = new TitledPane("Advanced", advancedFields); advanced.setExpanded(false);
        VBox form = new VBox(14, SearchSettingsPane.hint("Connect a local or OpenAI-compatible embedding service."),
                field("Display name", name), field("API Base URL", url), field("Model ID", model), field("API Key", key), advanced,
                SearchSettingsPane.hint("Local providers are recommended. The provider receives note text and search queries when semantic search is enabled."),
                test, feedback);
        form.setPadding(new Insets(4, 4, 8, 4));
        ScrollPane scroll = new ScrollPane(form); scroll.setFitToWidth(true); scroll.getStyleClass().add("content-scroll");
        scroll.setMaxHeight(620); getDialogPane().setContent(scroll);
        ButtonType saveType = new ButtonType(existing == null ? "Add model" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, saveType);
        Button save = (Button) getDialogPane().lookupButton(saveType);
        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        save.setId("save-embedding-model"); save.getStyleClass().addAll("action-button", "primary"); cancel.getStyleClass().add("search-secondary");
        save.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                SavedEmbeddingModel edited = readModel();
                if (!edited.config().configured()) throw new IllegalArgumentException("Set API Base URL and Model ID");
                edited.config().endpoint();
                EmbeddingModelCatalog before = service.modelCatalogProperty().get();
                ArrayList<SavedEmbeddingModel> models = new ArrayList<>(before.models());
                int position = -1;
                for (int i = 0; i < models.size(); i++) if (models.get(i).id().equals(id)) position = i;
                if (position < 0) models.add(edited); else models.set(position, edited);
                EmbeddingModelCatalog next = new EmbeddingModelCatalog(models, before.selectedId(), before.enabled());
                stopProbe(); saving = true; form.setDisable(true); save.setDisable(true); cancel.setDisable(true);
                feedback.setText("Saving…");
                Task<Void> task = service.saveModelCatalog(next);
                task.setOnSucceeded(e -> { saving = false; close(); });
                task.setOnFailed(e -> {
                    saving = false; form.setDisable(false); save.setDisable(false); cancel.setDisable(false);
                    feedback.setText("Save failed: " + LocalSearchService.message(task.getException()));
                });
            } catch (IllegalArgumentException error) { feedback.setText(error.getMessage()); }
        });
        test.setOnAction(e -> {
            try {
                EmbeddingConfig config = readModel().config(); config.endpoint();
                stopProbe(); test.setDisable(true); feedback.setText("Testing connection…");
                Task<Integer> task = service.testConnection(config); probe = task;
                task.setOnSucceeded(event -> {
                    if (probe == task && isShowing()) { test.setDisable(false); feedback.setText("Connection verified · " + task.getValue() + " dimensions"); probe = null; }
                });
                task.setOnFailed(event -> {
                    if (probe == task && isShowing()) { test.setDisable(false); feedback.setText("Connection failed: " + LocalSearchService.message(task.getException())); probe = null; }
                });
            } catch (IllegalArgumentException error) { feedback.setText(error.getMessage()); }
        });
        for (TextInputControl field : new TextInputControl[]{name, url, model, key, documentPrefix, queryPrefix}) {
            field.textProperty().addListener((o, old, value) -> { stopProbe(); feedback.setText(""); });
        }
        setOnCloseRequest(e -> { if (saving) e.consume(); });
        setOnHidden(e -> stopProbe());
    }

    private SavedEmbeddingModel readModel() {
        return new SavedEmbeddingModel(id, name.getText(), new EmbeddingConfig(false, url.getText(), model.getText(), key.getText(), documentPrefix.getText(), queryPrefix.getText()));
    }
    private void stopProbe() {
        if (probe != null) { probe.cancel(false); probe = null; }
        test.setDisable(false);
    }
    void dispose() { setOnCloseRequest(null); close(); stopProbe(); }
    private static VBox field(String text, TextInputControl input) {
        input.getStyleClass().add("input-field");
        return new VBox(7, SearchSettingsPane.label(text, "field-label"), input);
    }
}
