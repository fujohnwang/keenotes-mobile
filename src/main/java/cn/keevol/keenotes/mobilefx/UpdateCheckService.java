package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.generated.BuildInfo;
import io.vertx.core.json.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * Service for checking application updates
 */
public class UpdateCheckService {
    
    private static final String VERSION_API_URL = "https://kns.afoo.me/version/latest";
    private static final String CURRENT_VERSION = BuildInfo.VERSION;
    
    private final OkHttpClient httpClient;
    private UpdateListener listener;
    
    public UpdateCheckService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * Check for updates in background thread
     */
    public void checkForUpdates() {
        // Skip check for dev builds
        if ("dev".equals(CURRENT_VERSION)) {
            System.out.println("[UpdateCheck] Skipping update check for dev build");
            return;
        }
        
        Thread checkThread = new Thread(() -> {
            try {
                System.out.println("[UpdateCheck] Checking for updates... Current version: " + CURRENT_VERSION);
                
                Request request = new Request.Builder()
                        .url(VERSION_API_URL)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        System.err.println("[UpdateCheck] Failed to check updates: HTTP " + response.code());
                        return;
                    }
                    
                    String body = response.body().string();
                    JsonObject json = new JsonObject(body);
                    
                    String latestVersion = json.getString("version");
                    String downloadUrl = json.getString("url");
                    
                    System.out.println("[UpdateCheck] Latest version: " + latestVersion);
                    
                    if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                        System.out.println("[UpdateCheck] New version available: " + latestVersion);
                        notifyUpdateAvailable(latestVersion, downloadUrl);
                    } else {
                        System.out.println("[UpdateCheck] Already on latest version");
                    }
                }
            } catch (Exception e) {
                System.err.println("[UpdateCheck] Error checking for updates: " + e.getMessage());
            }
        }, "UpdateCheck");
        
        checkThread.setDaemon(true);
        checkThread.start();
    }
    
    /**
     * Compare two semantic versions
     * Returns true if latest > current
     */
    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            
            return false; // Versions are equal
        } catch (Exception e) {
            System.err.println("[UpdateCheck] Error comparing versions: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Notify listener about available update
     */
    private void notifyUpdateAvailable(String version, String url) {
        if (listener != null) {
            javafx.application.Platform.runLater(() -> {
                listener.onUpdateAvailable(version, url);
            });
        }
    }
    
    /**
     * Set update listener
     */
    public void setUpdateListener(UpdateListener listener) {
        this.listener = listener;
    }
    
    /**
     * Listener interface for update notifications
     */
    public interface UpdateListener {
        void onUpdateAvailable(String version, String downloadUrl);
    }
}
