package cn.keevol.keenotes.mobilefx;

import javafx.scene.control.ListCell;

/**
 * ListView cell that REUSES a single NoteCardView per cell instance.
 * VirtualFlow recycles cells; update() avoids creating new TextArea/Canvas per
 * scroll,
 * preventing GPU texture exhaustion.
 */
public class NoteListCell extends ListCell<LocalCacheService.NoteData> {

    private final NotesDisplayPanel panel;
    private NoteCardView card;

    public NoteListCell(NotesDisplayPanel panel) {
        this.panel = panel;
        setStyle("-fx-padding: 0 0 12 0; -fx-background-color: transparent;");
    }

    @Override
    protected void updateItem(LocalCacheService.NoteData item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
        } else {
            if (card == null) {
                card = new NoteCardView(item);
            } else {
                card.update(item);
            }
            setGraphic(card);
            // Start border animation for optimistic note
            if (item == panel.getOptimisticNoteData()) {
                card.startBorderAnimation();
                panel.setOptimisticCard(card);
            }
        }
    }
}
