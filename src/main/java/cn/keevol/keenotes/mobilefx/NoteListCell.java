package cn.keevol.keenotes.mobilefx;

import javafx.scene.control.ListCell;

/** VirtualFlow reuses the card and its selectable text area as the list scrolls. */
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
            boolean samePendingNote = wasOptimistic && card != null && card.getNoteData() == item;
            boolean resolvingOptimistic = wasOptimistic && card != null && item.id > 0
                    && item.content.equals(card.getNoteData().content);

            if (card == null) {
                card = new NoteCardView(item, panel.getOnReviseNote());
            } else if (resolvingOptimistic) {
                card.resolveWithRealNote(item);
                wasOptimistic = false;
            } else {
                card.update(item);
            }
            card.setSearchSources(panel.isKeywordResult(item.id), panel.isSemanticResult(item.id));
            setGraphic(card);

            // Handle optimistic note animation
            boolean isOptimistic = (item == panel.getOptimisticNoteData());
            if (isOptimistic) {
                if (!samePendingNote) {
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
