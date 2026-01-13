package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Overview Card component showing total notes count and days using KeeNotes
 */
public class OverviewCard extends HBox {
    
    private final Label totalNotesValue;
    private final Label daysUsingValue;
    private final IntegerProperty totalNotesProperty = new SimpleIntegerProperty(0);
    private final IntegerProperty daysUsingProperty = new SimpleIntegerProperty(0);
    
    public OverviewCard() {
        getStyleClass().add("overview-card");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(16));
        setSpacing(0);
        
        // Total Notes section
        VBox totalNotesBox = new VBox(8);
        totalNotesBox.setAlignment(Pos.CENTER);
        totalNotesBox.getStyleClass().add("overview-section");
        
        totalNotesValue = new Label("0");
        totalNotesValue.getStyleClass().add("overview-value");
        
        Label totalNotesLabel = new Label("Notes (7 days)");
        totalNotesLabel.getStyleClass().add("overview-label");
        
        totalNotesBox.getChildren().addAll(totalNotesValue, totalNotesLabel);
        
        // Divider
        Region divider = new Region();
        divider.getStyleClass().add("overview-divider");
        divider.setPrefWidth(1);
        divider.setMaxHeight(Double.MAX_VALUE);
        HBox.setMargin(divider, new Insets(0, 16, 0, 16));
        
        // Days Using section
        VBox daysUsingBox = new VBox(8);
        daysUsingBox.setAlignment(Pos.CENTER);
        daysUsingBox.getStyleClass().add("overview-section");
        
        daysUsingValue = new Label("0");
        daysUsingValue.getStyleClass().add("overview-value");
        
        Label daysUsingLabel = new Label("Days Using");
        daysUsingLabel.getStyleClass().add("overview-label");
        
        daysUsingBox.getChildren().addAll(daysUsingValue, daysUsingLabel);
        
        // Set equal width for both sections
        HBox.setHgrow(totalNotesBox, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(daysUsingBox, javafx.scene.layout.Priority.ALWAYS);
        
        getChildren().addAll(totalNotesBox, divider, daysUsingBox);
        
        // Bind labels to properties
        totalNotesProperty.addListener((obs, oldVal, newVal) -> 
            totalNotesValue.setText(String.valueOf(newVal.intValue())));
        
        daysUsingProperty.addListener((obs, oldVal, newVal) -> 
            daysUsingValue.setText(String.valueOf(newVal.intValue())));
        
        // Start update thread
        startUpdateThread();
    }
    
    /**
     * Update total notes count
     */
    public void updateTotalNotes(int count) {
        Platform.runLater(() -> totalNotesProperty.set(count));
        
        // Initialize first note date if needed
        if (count > 0) {
            SettingsService settings = SettingsService.getInstance();
            if (settings.getFirstNoteDate() == null) {
                initializeFirstNoteDate();
            }
        }
    }
    
    /**
     * Update days using count
     */
    public void updateDaysUsing() {
        SettingsService settings = SettingsService.getInstance();
        String firstDateStr = settings.getFirstNoteDate();
        
        if (firstDateStr == null) {
            Platform.runLater(() -> daysUsingProperty.set(0));
            return;
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            LocalDate firstDate = LocalDate.parse(firstDateStr.substring(0, 10));
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(firstDate, today) + 1;
            
            Platform.runLater(() -> daysUsingProperty.set((int) days));
        } catch (Exception e) {
            Platform.runLater(() -> daysUsingProperty.set(0));
        }
    }
    
    /**
     * Initialize first note date from database
     */
    private void initializeFirstNoteDate() {
        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                if (serviceManager.getLocalCacheState() == ServiceManager.InitializationState.READY) {
                    LocalCacheService cache = serviceManager.getLocalCacheService();
                    String oldestDate = cache.getOldestNoteDate();
                    
                    if (oldestDate != null) {
                        SettingsService settings = SettingsService.getInstance();
                        settings.setFirstNoteDate(oldestDate);
                        settings.save();
                        updateDaysUsing();
                    }
                }
            } catch (Exception e) {
                System.err.println("[OverviewCard] Failed to initialize first note date: " + e.getMessage());
            }
        }, "InitFirstNoteDate").start();
    }
    
    /**
     * Start background thread to update note count and days using
     */
    private void startUpdateThread() {
        Thread updateThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // Update every 5 seconds
                    
                    ServiceManager serviceManager = ServiceManager.getInstance();
                    if (serviceManager.getLocalCacheState() == ServiceManager.InitializationState.READY) {
                        LocalCacheService cache = serviceManager.getLocalCacheService();
                        int count = cache.getLocalNoteCount();
                        updateTotalNotes(count);
                        updateDaysUsing();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Continue on error
                }
            }
        }, "OverviewCardUpdate");
        updateThread.setDaemon(true);
        updateThread.start();
    }
}
