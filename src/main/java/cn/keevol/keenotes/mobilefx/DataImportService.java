package cn.keevol.keenotes.mobilefx;

import io.vertx.core.json.JsonObject;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Service for importing notes from NDJSON files
 */
public class DataImportService {
    
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ApiServiceV2 apiService;
    private volatile boolean isImporting = false;
    private volatile boolean shouldCancel = false;
    
    public DataImportService(ApiServiceV2 apiService) {
        this.apiService = apiService;
    }
    
    /**
     * Validation result for NDJSON file
     */
    public record ValidationResult(boolean valid, String errorMessage, int lineCount) {
        public static ValidationResult success(int lineCount) {
            return new ValidationResult(true, null, lineCount);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, 0);
        }
    }
    
    /**
     * Import progress callback
     */
    public interface ImportProgressListener {
        void onProgress(int current, int total);
        void onComplete(int success, int failed, String failedFilePath);
        void onError(String error);
    }
    
    /**
     * Validate NDJSON file format
     */
    public CompletableFuture<ValidationResult> validateFile(File file) {
        return CompletableFuture.supplyAsync(() -> {
            if (file == null || !file.exists()) {
                return ValidationResult.error("File does not exist");
            }
            
            if (!file.canRead()) {
                return ValidationResult.error("Cannot read file");
            }
            
            int lineCount = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue; // Skip empty lines
                    }
                    
                    lineCount++;
                    
                    // Parse JSON
                    try {
                        JsonObject json = new JsonObject(line);
                        
                        // Validate required fields
                        if (!json.containsKey("content") || json.getString("content") == null || json.getString("content").trim().isEmpty()) {
                            return ValidationResult.error("Line " + lineCount + ": Missing or empty 'content' field");
                        }
                        
                        if (!json.containsKey("channel") || json.getString("channel") == null || json.getString("channel").trim().isEmpty()) {
                            return ValidationResult.error("Line " + lineCount + ": Missing or empty 'channel' field");
                        }
                        
                        // Check for timestamp field (created_at or ts)
                        boolean hasTimestamp = (json.containsKey("created_at") && json.getString("created_at") != null && !json.getString("created_at").trim().isEmpty()) ||
                                             (json.containsKey("ts") && json.getString("ts") != null && !json.getString("ts").trim().isEmpty());
                        
                        if (!hasTimestamp) {
                            return ValidationResult.error("Line " + lineCount + ": Missing 'created_at' or 'ts' field");
                        }
                        
                    } catch (Exception e) {
                        return ValidationResult.error("Line " + lineCount + ": Invalid JSON format - " + e.getMessage());
                    }
                }
                
                if (lineCount == 0) {
                    return ValidationResult.error("File is empty");
                }
                
                return ValidationResult.success(lineCount);
                
            } catch (Exception e) {
                return ValidationResult.error("Error reading file: " + e.getMessage());
            }
        });
    }
    
    /**
     * Import notes from NDJSON file
     */
    public CompletableFuture<Void> importFile(File file, ImportProgressListener listener) {
        if (isImporting) {
            Platform.runLater(() -> listener.onError("Import already in progress"));
            return CompletableFuture.completedFuture(null);
        }
        
        isImporting = true;
        shouldCancel = false;
        
        return CompletableFuture.runAsync(() -> {
            List<String> failedLines = new ArrayList<>();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failedCount = new AtomicInteger(0);
            AtomicInteger totalLines = new AtomicInteger(0);
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                // First pass: count total lines
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        totalLines.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                Platform.runLater(() -> listener.onError("Error counting lines: " + e.getMessage()));
                isImporting = false;
                return;
            }
            
            int total = totalLines.get();
            AtomicInteger current = new AtomicInteger(0);
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                int lineNumber = 0;
                
                while ((line = reader.readLine()) != null && !shouldCancel) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    
                    lineNumber++;
                    final int currentLine = lineNumber;
                    final String currentLineContent = line;
                    
                    try {
                        JsonObject json = new JsonObject(line);
                        
                        // Extract fields
                        String content = json.getString("content");
                        String channel = json.getString("channel");
                        String timestamp = json.containsKey("created_at") ? 
                                         json.getString("created_at") : 
                                         json.getString("ts");
                        Boolean encrypted = json.getBoolean("encrypted", false);
                        
                        // Send note based on encryption status
                        CompletableFuture<ApiServiceV2.ApiResult> sendFuture;
                        
                        if (encrypted != null && encrypted) {
                            // Already encrypted - send directly without encryption
                            sendFuture = apiService.postNoteDirectly(content, channel, timestamp);
                        } else {
                            // Not encrypted - use normal E2EE flow
                            sendFuture = apiService.postNote(content, channel, timestamp);
                        }
                        
                        // Wait for result
                        ApiServiceV2.ApiResult result = sendFuture.get();
                        
                        if (result.success()) {
                            successCount.incrementAndGet();
                        } else {
                            failedCount.incrementAndGet();
                            failedLines.add("Line " + currentLine + ": " + result.message() + " | Data: " + currentLineContent);
                        }
                        
                    } catch (Exception e) {
                        failedCount.incrementAndGet();
                        failedLines.add("Line " + currentLine + ": " + e.getMessage() + " | Data: " + currentLineContent);
                    }
                    
                    // Update progress
                    int prog = current.incrementAndGet();
                    Platform.runLater(() -> listener.onProgress(prog, total));
                    
                    // Small delay to avoid overwhelming the server
                    Thread.sleep(100);
                }
                
                // Write failed lines to file if any
                String failedFilePath = null;
                if (!failedLines.isEmpty()) {
                    failedFilePath = writeFailedLines(file, failedLines);
                }
                
                final String finalFailedFilePath = failedFilePath;
                Platform.runLater(() -> listener.onComplete(successCount.get(), failedCount.get(), finalFailedFilePath));
                
            } catch (Exception e) {
                Platform.runLater(() -> listener.onError("Import error: " + e.getMessage()));
            } finally {
                isImporting = false;
            }
        });
    }
    
    /**
     * Write failed lines to a temporary file
     */
    private String writeFailedLines(File originalFile, List<String> failedLines) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "import_failed_" + timestamp + ".txt";
            Path failedFile = Paths.get(System.getProperty("user.home"), fileName);
            
            StringBuilder content = new StringBuilder();
            content.append("Import Failed Lines Report\n");
            content.append("Original File: ").append(originalFile.getAbsolutePath()).append("\n");
            content.append("Timestamp: ").append(LocalDateTime.now().format(TS_FORMATTER)).append("\n");
            content.append("Total Failed: ").append(failedLines.size()).append("\n");
            content.append("=".repeat(80)).append("\n\n");
            
            for (String failedLine : failedLines) {
                content.append(failedLine).append("\n");
            }
            
            Files.writeString(failedFile, content.toString());
            return failedFile.toString();
            
        } catch (Exception e) {
            System.err.println("[DataImportService] Failed to write failed lines file: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Cancel ongoing import
     */
    public void cancelImport() {
        shouldCancel = true;
    }
    
    /**
     * Check if import is in progress
     */
    public boolean isImporting() {
        return isImporting;
    }
}
