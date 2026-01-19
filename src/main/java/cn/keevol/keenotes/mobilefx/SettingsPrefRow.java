package cn.keevol.keenotes.mobilefx;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

public record SettingsPrefRow(HBox row, Label titleLabel, Node node, Label messageLabel) {

}
