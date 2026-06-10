package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Overview Card component showing total notes count and days using KeeNotes
 */
public class OverviewCard extends HBox {
    
    private final Label totalNotesValue;
    private final Label daysUsingValue;
    private final IntegerProperty totalNotesProperty = new SimpleIntegerProperty(0);
    private final IntegerProperty daysUsingProperty = new SimpleIntegerProperty(0);

    // Listener references for dispose pattern
    private ChangeListener<Number> accountSwitchListener;
    private LocalCacheService.NoteChangeListener localCacheChangeListener;
    private volatile boolean disposed = false;
    
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
        
        Label totalNotesLabel = new Label("Total Notes");
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
        totalNotesProperty.addListener((obs, oldVal, newVal) -> {
            totalNotesValue.setText(String.valueOf(newVal.intValue()));
            
            // 方案 A: 当 Total Notes 从 0 变为非 0 时，更新 Days Using
            if (oldVal.intValue() == 0 && newVal.intValue() > 0) {
                initializeFirstNoteDateIfNeeded();
            }
        });
        
        daysUsingProperty.addListener((obs, oldVal, newVal) -> 
            daysUsingValue.setText(String.valueOf(newVal.intValue())));
        
        // Initialize data binding
        initializeDataBinding();
    }
    
    /**
     * Initialize reactive data binding
     */
    private void initializeDataBinding() {
        // 监听账户切换事件（响应式）
        accountSwitchListener = (obs, oldVal, newVal) -> {
            // Property 值变化时自动触发，直接重置
            System.out.println("[OverviewCard] Account switched detected! oldVal: " + oldVal + ", newVal: " + newVal);
            reset();
        };
        ServiceManager.getInstance().accountSwitchedProperty().addListener(accountSwitchListener);
        
        new Thread(() -> {
            try {
                // Wait for ServiceManager to be ready
                ServiceManager serviceManager = ServiceManager.getInstance();
                while (serviceManager.getLocalCacheState() != ServiceManager.InitializationState.READY
                        && !disposed) {
                    Thread.sleep(100);
                }
                // Check again after loop — service might never become ready or we might be disposed
                if (disposed || serviceManager.getLocalCacheState() != ServiceManager.InitializationState.READY) {
                    return;
                }

                LocalCacheService cache = serviceManager.getLocalCacheService();

                // Bind to note count property
                Platform.runLater(() -> {
                    if (!disposed) {
                        totalNotesProperty.bind(cache.noteCountProperty());
                    }
                });

                // 方案 B: 监听批量插入事件
                localCacheChangeListener = new LocalCacheService.NoteChangeListener() {
                    @Override
                    public void onNoteInserted(LocalCacheService.NoteData note) {
                        if (disposed) return;
                        initializeFirstNoteDateIfNeeded();
                    }

                    @Override
                    public void onNotesInserted(java.util.List<LocalCacheService.NoteData> notes) {
                        if (disposed) return;
                        initializeFirstNoteDateIfNeeded();
                    }
                };
                cache.addChangeListener(localCacheChangeListener);

                // Initialize first note date if needed
                if (cache.getLocalNoteCount() > 0) {
                    initializeFirstNoteDateIfNeeded();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[OverviewCard] Failed to initialize data binding: " + e.getMessage());
            }
        }, "OverviewCardInit").start();
    }
    
    /**
     * Remove all listeners registered on singleton services.
     */
    public void dispose() {
        disposed = true;
        ServiceManager.getInstance().accountSwitchedProperty().removeListener(accountSwitchListener);
        if (localCacheChangeListener != null) {
            ServiceManager.getInstance().getLocalCacheService().removeChangeListener(localCacheChangeListener);
        }
        totalNotesProperty.unbind();
    }

    /**
     * Reset both metrics to 0 (called when account is switched)
     */
    public void reset() {
        System.out.println("[OverviewCard] reset() called - resetting both metrics to 0");
        Platform.runLater(() -> {
            // totalNotesProperty 已经绑定到 cache.noteCountProperty，直接更新源头
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                if (serviceManager.getLocalCacheState() == ServiceManager.InitializationState.READY) {
                    LocalCacheService cache = serviceManager.getLocalCacheService();
                    cache.noteCountProperty().set(0);
                    System.out.println("[OverviewCard] Set cache.noteCountProperty to 0");
                }
            } catch (Exception e) {
                System.err.println("[OverviewCard] Failed to reset noteCount: " + e.getMessage());
            }
            
            // daysUsingProperty 没有绑定，直接设置
            daysUsingProperty.set(0);
            System.out.println("[OverviewCard] Set daysUsingProperty to 0");
        });
    }
    
    /**
     * Update total notes count (called when note count property changes)
     */
    public void updateTotalNotes(int count) {
        Platform.runLater(() -> totalNotesProperty.set(count));
        
        // Initialize first note date if needed
        if (count > 0) {
            initializeFirstNoteDateIfNeeded();
        }
    }
    
    /**
     * Initialize first note date if needed (unified method)
     */
    private void initializeFirstNoteDateIfNeeded() {
        // Always update firstNoteDate when notes change
        // This ensures we capture the earliest note even after sync
        initializeFirstNoteDate();
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
            LocalDate firstDate = parseUtcStorageToLocalDate(firstDateStr);
            if (firstDate == null) {
                Platform.runLater(() -> daysUsingProperty.set(0));
                return;
            }
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(firstDate, today) + 1;

            Platform.runLater(() -> daysUsingProperty.set((int) days));
        } catch (Exception e) {
            Platform.runLater(() -> daysUsingProperty.set(0));
        }
    }
    
    private static LocalDate parseUtcStorageToLocalDate(String utcStorage) {
        if (utcStorage == null || utcStorage.isBlank()) {
            return null;
        }
        String trimmed = utcStorage.trim();
        DateTimeFormatter storageFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        try {
            LocalDateTime utcDateTime = LocalDateTime.parse(trimmed, storageFormatter);
            return utcDateTime.atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDate();
        } catch (DateTimeParseException e) {
            LocalDateTime parsed = DateTimeUtil.tryParse(trimmed);
            if (parsed == null) {
                return null;
            }
            return parsed.atZone(ZoneId.systemDefault()).toLocalDate();
        }
    }

    /**
     * Initialize first note date from database
     */
    private void initializeFirstNoteDate() {
        Thread t = new Thread(() -> {
            if (disposed) return;
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
        }, "InitFirstNoteDate");
        t.setDaemon(true);
        t.start();
    }
}
