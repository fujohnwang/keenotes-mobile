package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.search.*;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;

/** Saved models are the default view; forms only appear when adding or configuring one. */
final class SearchSettingsPane extends VBox {
    private final LocalSearchService service = ServiceManager.getInstance().getLocalSearchService();
    private final ToggleGroup modelSelection = new ToggleGroup();
    private final BooleanProperty semanticEnabled = new SimpleBooleanProperty();
    private final FlowPane cards = new FlowPane(16, 16);
    private final Label message = hint("");
    private final Label keywordStatus = hint("Initializing keyword index…");
    private final Label vectorStatus = hint("");
    private final Label keywordBuild = hint("");
    private final Label vectorBuild = hint("");
    private final Label keywordError = hint("");
    private final Label vectorError = hint("");
    private final BooleanProperty saving = new SimpleBooleanProperty();
    private final ChangeListener<LocalSearchEngine.Status> statusListener = (o, old, value) -> showStatus(value);
    private final ChangeListener<EmbeddingModelCatalog> catalogListener = (o, old, value) -> showModels(value);
    private final DoubleBinding cardWidth = Bindings.createDoubleBinding(() -> {
        double width = Math.max(cards.getWidth(), 260);
        int columns = width >= 1040 ? 4 : width >= 780 ? 3 : width >= 520 ? 2 : 1;
        return Math.floor((width - (columns - 1) * 16) / columns);
    }, cards.widthProperty());
    private EmbeddingModelDialog editor;
    private boolean disposed;

    SearchSettingsPane() {
        super(24);
        setPadding(new Insets(24, 0, 8, 0));
        getStyleClass().add("search-settings");
        Label modelsTitle = label("Embedding models", "search-section-title");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button add = button("+ Add model", false); add.setId("add-embedding-model");
        add.disableProperty().bind(saving);
        add.setOnAction(e -> edit(null));
        HBox modelsHeader = new HBox(16, modelsTitle, spacer, add); modelsHeader.setAlignment(Pos.CENTER_LEFT);
        cards.setId("embedding-model-cards");
        cards.setMinWidth(0);
        VBox providers = new VBox(16, modelsHeader, cards);
        message.managedProperty().bind(message.textProperty().isNotEmpty());
        message.visibleProperty().bind(message.managedProperty());

        keywordBuild.textProperty().bind(service.keywordBuildProperty());
        vectorBuild.textProperty().bind(service.vectorBuildProperty());
        keywordError.textProperty().bind(service.keywordErrorProperty());
        vectorError.textProperty().bind(service.vectorErrorProperty());
        getChildren().addAll(
                section("Keyword Search Settings", "keyword-search-section", indexPanel(false)),
                section("Semantic Search Settings", "semantic-search-section", indexPanel(true), providers, message));
        service.statusProperty().addListener(statusListener);
        service.modelCatalogProperty().addListener(catalogListener);
        showModels(catalog()); showStatus(service.statusProperty().get());
    }

    /** A top-level settings block: heading, rule, then the settings that belong to it. */
    private static VBox section(String title, String id, Node... content) {
        Region rule = new Region();
        rule.getStyleClass().add("search-section-rule");
        rule.setMaxWidth(Double.MAX_VALUE);
        VBox section = new VBox(16, new VBox(10, label(title, "search-group-title"), rule));
        section.getChildren().addAll(content);
        section.setId(id);
        section.setMinWidth(0);
        return section;
    }

    private EmbeddingModelCatalog catalog() { return service.modelCatalogProperty().get(); }

    private VBox indexPanel(boolean vector) {
        var busy = vector ? service.vectorRebuildingProperty() : service.keywordRebuildingProperty();
        Label error = vector ? vectorError : keywordError;
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        Button rebuild = button("Rebuild index", true);
        Button cancel = button("Cancel", false);
        Button retry = button("Retry failed notes", false);
        String family = vector ? "semantic" : "keyword";
        rebuild.setId("rebuild-" + family); cancel.setId("cancel-" + family); retry.setId("retry-" + family);
        rebuild.setOnAction(e -> { if (vector) service.rebuildVectors(); else service.rebuildKeywords(); });
        cancel.setOnAction(e -> { if (vector) service.cancelVectorRebuild(); else service.cancelKeywordRebuild(); });
        retry.setOnAction(e -> { if (vector) service.retryVectorFailures(); else service.retryKeywordFailures(); });
        rebuild.disableProperty().bind(vector ? busy.or(semanticEnabled.not()).or(saving) : busy);
        cancel.disableProperty().bind(busy.not());
        if (vector) retry.disableProperty().bind(semanticEnabled.not().or(saving));
        FlowPane actions = new FlowPane(10, 10, rebuild, cancel, retry);
        VBox panel = new VBox(16, label(vector ? "Semantic index" : "Keyword index", "search-section-title"),
                vector ? vectorStatus : keywordStatus, vector ? vectorBuild : keywordBuild, actions, error,
                hint(vector ? "Select a model, then rebuild to include historical notes."
                        : "Rebuild to include all historical notes. New synced notes are indexed automatically."));
        panel.setId(family + "-index-panel");
        panel.getStyleClass().add("search-index-panel");
        panel.setMinWidth(0); panel.setMaxWidth(Double.MAX_VALUE);
        return panel;
    }

    private void showModels(EmbeddingModelCatalog catalog) {
        if (disposed) return;
        semanticEnabled.set(catalog.enabled());
        clearCards();
        cards.getChildren().clear();
        cards.getChildren().add(modelCard(null, catalog.selected() == null));
        for (SavedEmbeddingModel model : catalog.models()) cards.getChildren().add(modelCard(model, model.id().equals(catalog.selectedId())));
        showStatus(service.statusProperty().get());
    }

    private ToggleButton modelCard(SavedEmbeddingModel model, boolean selected) {
        String id = model == null ? "none" : model.id();
        Label name = label(model == null ? "None" : model.name(), "model-card-name");
        name.setMaxWidth(Double.MAX_VALUE); name.setWrapText(true);
        Region headerSpacer = new Region(); HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        SVGPath check = new SVGPath();
        check.setContent("M2 8 L6 12 L14 3");
        check.getStyleClass().add("model-card-check");
        check.setVisible(selected); check.setManaged(selected);
        HBox header = new HBox(12, name, headerSpacer, check); header.setAlignment(Pos.CENTER_LEFT);
        Label modelId = label(model == null ? "Keyword search only" : model.config().model(), "model-id"); modelId.setWrapText(true);
        Label endpoint = hint(model == null ? "No model requests. Saved models stay available." : model.config().baseUrl());
        VBox details = new VBox(6, modelId, endpoint);
        VBox content = new VBox(16, header, details);
        content.prefWidthProperty().bind(cardWidth.subtract(42));
        content.setMouseTransparent(true);
        ToggleButton select = new ToggleButton() {
            @Override protected double computePrefHeight(double width) {
                // FlowPane measures with -1; the wrapping graphic needs the known column width.
                return super.computePrefHeight(width < 0 ? cardWidth.get() : width);
            }
        };
        select.setGraphic(content);
        select.setWrapText(true); // Propagate width-dependent sizing from the card's graphic.
        select.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        select.setAlignment(Pos.TOP_LEFT);
        select.setId("embedding-model-" + id);
        select.setAccessibleText(model == null ? "None, keyword search only" : "Use " + model.name() + " for semantic search");
        select.getStyleClass().add("embedding-model-card");
        if (selected) select.getStyleClass().add("selected-model-card");
        select.setToggleGroup(modelSelection); select.setSelected(selected);
        select.disableProperty().bind(saving);
        select.setOnAction(e -> {
            select.setSelected(true); // Keep one card selected, including when clicking it again.
            if (model == null) {
                if (catalog().enabled()) save(new EmbeddingModelCatalog(catalog().models(), "", false),
                        "Semantic search is off. Saved models and indexes are kept.");
            } else if (!catalog().selectedId().equals(model.id())) save(new EmbeddingModelCatalog(catalog().models(), model.id(), true),
                    "Semantic search enabled. Rebuild the semantic index to include all notes.");
        });
        select.setMinWidth(0); select.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (model != null) {
            // Editing lives on the card's context menu so clicking the card only ever selects it.
            // No disable binding needed: a disabled card does not receive mouse events while saving.
            MenuItem configure = new MenuItem("Configure");
            configure.setOnAction(e -> edit(model));
            select.setContextMenu(new ContextMenu(configure));
        }
        select.prefWidthProperty().bind(cardWidth);
        return select;
    }

    private void edit(SavedEmbeddingModel model) {
        if (editor != null && editor.isShowing()) { editor.getDialogPane().requestFocus(); return; }
        editor = new EmbeddingModelDialog(getScene(), service, model);
        editor.show();
    }

    private void save(EmbeddingModelCatalog next, String success) {
        saving.set(true);
        var task = service.saveModelCatalog(next);
        task.setOnSucceeded(e -> { if (!disposed) { saving.set(false); message.setText(success); } });
        task.setOnFailed(e -> { if (!disposed) { saving.set(false); showModels(catalog()); message.setText("Save failed: " + LocalSearchService.message(task.getException())); } });
    }

    private void showStatus(LocalSearchEngine.Status status) {
        if (status == null) return;
        keywordStatus.setText(describe(status.keywords()));
        vectorStatus.setText(catalog().enabled() ? describe(status.vectors()) : "Semantic search is off");
    }

    /** Historical is the fixed rebuild snapshot; everything synced since lands in the incremental layer. */
    private static String describe(LocalSearchEngine.Layer layer) {
        return "Historical index  " + (layer.built() ? layer.base() + " notes" : "not built")
                + "\nIncremental index  " + layer.delta() + " notes · " + layer.pending() + " pending · " + layer.failed() + " failed";
    }

    static Label hint(String text) { Label label = label(text, "field-hint"); label.setWrapText(true); return label; }
    static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    static Button button(String text, boolean primary) {
        Button button = new Button(text); button.getStyleClass().addAll(primary ? new String[]{"action-button", "primary"} : new String[]{"search-secondary"}); return button;
    }
    private void clearCards() {
        modelSelection.getToggles().clear();
        cards.getChildren().forEach(node -> {
            if (node instanceof Region region) region.prefWidthProperty().unbind();
            node.lookupAll(".toggle-button").forEach(control -> {
                control.disableProperty().unbind();
                ((Region) ((ToggleButton) control).getGraphic()).prefWidthProperty().unbind();
            });
        });
    }
    void dispose() {
        disposed = true;
        if (editor != null) editor.dispose();
        service.statusProperty().removeListener(statusListener);
        service.modelCatalogProperty().removeListener(catalogListener);
        keywordBuild.textProperty().unbind(); vectorBuild.textProperty().unbind();
        keywordError.textProperty().unbind(); vectorError.textProperty().unbind();
        clearCards();
        lookupAll(".button").forEach(node -> node.disableProperty().unbind());
        cardWidth.dispose();
    }
}
