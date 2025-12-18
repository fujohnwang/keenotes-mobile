package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.storage.StorageService;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Service for managing application settings.
 * Settings are persisted to local storage.
 */
public class SettingsService {

    private static final String SETTINGS_FILE = "keenotes.properties";
    private static final String KEY_ENDPOINT_URL = "endpoint.url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_REVIEW_DAYS = "review.days";
    private static final String KEY_ENCRYPTION_PASSWORD = "encryption.password";
    private static final int DEFAULT_REVIEW_DAYS = 7;

    private static SettingsService instance;
    private final Properties properties;
    private final Path settingsPath;

    private SettingsService() {
        properties = new Properties();
        settingsPath = resolveSettingsPath();
        loadSettings();
    }

    public static synchronized SettingsService getInstance() {
        if (instance == null) {
            instance = new SettingsService();
        }
        return instance;
    }

    private Path resolveSettingsPath() {
        return StorageService.create()
                .flatMap(StorageService::getPrivateStorage)
                .map(file -> file.toPath().resolve(SETTINGS_FILE))
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".keenotes", SETTINGS_FILE));
    }

    private void loadSettings() {
        if (Files.exists(settingsPath)) {
            try (InputStream is = Files.newInputStream(settingsPath)) {
                properties.load(is);
            } catch (IOException e) {
                System.err.println("Failed to load settings: " + e.getMessage());
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(settingsPath.getParent());
            try (OutputStream os = Files.newOutputStream(settingsPath)) {
                properties.store(os, "KeeNotes Settings");
            }
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    public String getEndpointUrl() {
        return properties.getProperty(KEY_ENDPOINT_URL, "");
    }

    public void setEndpointUrl(String url) {
        properties.setProperty(KEY_ENDPOINT_URL, url);
    }

    public String getToken() {
        return properties.getProperty(KEY_TOKEN, "");
    }

    public void setToken(String token) {
        properties.setProperty(KEY_TOKEN, token);
    }

    public boolean isConfigured() {
        return !getEndpointUrl().isBlank() && !getToken().isBlank();
    }

    public int getReviewDays() {
        try {
            return Integer.parseInt(properties.getProperty(KEY_REVIEW_DAYS, String.valueOf(DEFAULT_REVIEW_DAYS)));
        } catch (NumberFormatException e) {
            return DEFAULT_REVIEW_DAYS;
        }
    }

    public void setReviewDays(int days) {
        properties.setProperty(KEY_REVIEW_DAYS, String.valueOf(days));
    }

    public String getEncryptionPassword() {
        return properties.getProperty(KEY_ENCRYPTION_PASSWORD, "");
    }

    public void setEncryptionPassword(String password) {
        if (password == null || password.isEmpty()) {
            properties.remove(KEY_ENCRYPTION_PASSWORD);
        } else {
            properties.setProperty(KEY_ENCRYPTION_PASSWORD, password);
        }
    }

    public boolean isEncryptionEnabled() {
        String pwd = getEncryptionPassword();
        return pwd != null && !pwd.isEmpty();
    }
}
