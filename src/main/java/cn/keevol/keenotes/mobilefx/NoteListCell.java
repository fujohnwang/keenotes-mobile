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
    private boolean wasOptimistic = false;

    public NoteListCell(NotesDisplayPanel panel) {
        this.panel = panel;
        setStyle("-fx-padding: 0 0 12 0; -fx-background-color: transparent;");
        // Let cell width be determined by ListView, not by content
        setPrefWidth(0);
    }

    @Override
    protected void updateItem(LocalCacheService.NoteData item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            // Cancel any ongoing animation when cell is emptied
            if (wasOptimistic && card != null) {
                card.cancelBorderAnimation();
                wasOptimistic = false;
            }
        } else {
            if (card == null) {
                card = new NoteCardView(item);
            } else {
                card.update(item);
            }
            setGraphic(card);

            // Handle optimistic note animation
            boolean isOptimistic = (item == panel.getOptimisticNoteData());
            if (isOptimistic) {
                // Only start animation when transitioning from non-optimistic to optimistic
                // This prevents animation from resetting when scrolling through the list
                if (!wasOptimistic) {
                    card.startBorderAnimation();
                    panel.setOptimisticCard(card);
                    wasOptimistic = true;
                }
            } else if (wasOptimistic) {
                // Cell was showing optimistic note but now shows different note
                // Cancel animation to prevent it continuing on wrong item
                card.cancelBorderAnimation();
                wasOptimistic = false;
            }
        }
    }
}
