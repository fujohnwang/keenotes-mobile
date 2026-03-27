package cn.keevol.keenotes.mobilefx;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Share settings view — Hidden Watermark configuration.
 */
public class SettingsShareView extends VBox {

    private final SettingsService settings;
    private final TextField hiddenMessageField;
    private final Label statusLabel;

    public SettingsShareView() {
        this.settings = SettingsService.getInstance();

        setPadding(new Insets(24));
        setSpacing(16);
        setAlignment(Pos.TOP_CENTER);

        // Section title
        Label sectionTitle = new Label("Hidden Watermark");
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");

        Label sectionHint = new Label("When set, an invisible watermark is embedded into copied note content for traceability.");
        sectionHint.getStyleClass().add("field-hint");
        sectionHint.setWrapText(true);

        // Hidden message field
        hiddenMessageField = new TextField();
        hiddenMessageField.setPromptText("Enter hidden message...");
        hiddenMessageField.getStyleClass().add("input-field");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        // Save button
        Button saveButton = new Button("Save");
        saveButton.getStyleClass().addAll("action-button", "primary");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(e -> saveHiddenMessage());

        // Reactive binding: disable Save when field matches saved value
        saveButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> hiddenMessageField.getText().equals(settings.getHiddenMessage()),
                hiddenMessageField.textProperty()
        ));

        // Layout rows (same pattern as SettingsGeneralView)
        HBox fieldRow = createFieldRow("Message", hiddenMessageField);

        Label saveSpacer = new Label();
        saveSpacer.setMinWidth(259);
        saveSpacer.setMaxWidth(259);
        HBox.setHgrow(saveButton, Priority.ALWAYS);
        HBox saveRow = new HBox(16, saveSpacer, saveButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(259);
        statusSpacer.setMaxWidth(259);
        HBox statusRow = new HBox(16, statusSpacer, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(sectionTitle, sectionHint, fieldRow, saveRow, statusRow);

        loadSettings();
    }

    private HBox createFieldRow(String labelText, Control field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(16, label, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void loadSettings() {
        hiddenMessageField.setText(settings.getHiddenMessage());
    }

    private void saveHiddenMessage() {
        settings.setHiddenMessage(hiddenMessageField.getText());
        settings.save();
        statusLabel.setText("✓ Saved");
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");
    }
}
