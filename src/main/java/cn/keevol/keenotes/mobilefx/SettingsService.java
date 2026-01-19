package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.storage.StorageService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
    private static final String KEY_COPY_TO_CLIPBOARD = "copy.to.clipboard.on.post";
    private static final String KEY_SEARCH_SHORTCUT = "shortcut.search";
    private static final String KEY_SEND_SHORTCUT = "shortcut.send";
    private static final String KEY_SHOW_OVERVIEW_CARD = "show.overview.card";
    private static final String KEY_FIRST_NOTE_DATE = "first.note.date";
    private static final String KEY_THEME = "ui.theme";
    private static final String KEY_NOTE_FONT_SIZE = "note.font.size";
    private static final String KEY_ZOOM_IN_SHORTCUT = "shortcut.zoom.in";
    private static final String KEY_ZOOM_OUT_SHORTCUT = "shortcut.zoom.out";
    private static final String KEY_LOCAL_IMPORT_SERVER_PORT = "local.import.server.port";

    private static final int DEFAULT_REVIEW_DAYS = 7;
    private static final String DEFAULT_SEARCH_SHORTCUT = "Alt+Shift+S";
    private static final String DEFAULT_SEND_SHORTCUT = "Alt+Enter";
    private static final String DEFAULT_THEME = "dark";
    private static final int DEFAULT_NOTE_FONT_SIZE = 14;
    private static final int MIN_NOTE_FONT_SIZE = 10;
    private static final int MAX_NOTE_FONT_SIZE = 24;
    private static final int FONT_SIZE_STEP = 2;
    private static final String DEFAULT_ZOOM_IN_SHORTCUT = "Meta+EQUALS";
    private static final String DEFAULT_ZOOM_OUT_SHORTCUT = "Meta+MINUS";


    private static SettingsService instance;
    private final Properties properties;
    private final Path settingsPath;

    // JavaFX Property for reactive binding
    private final BooleanProperty showOverviewCardProperty = new SimpleBooleanProperty(true);
    private final IntegerProperty noteFontSizeProperty = new SimpleIntegerProperty(DEFAULT_NOTE_FONT_SIZE);

    private SettingsService() {
        properties = new Properties();
        settingsPath = resolveSettingsPath();
        loadSettings();
        // Initialize property from loaded settings
        showOverviewCardProperty.set(getShowOverviewCard());
        noteFontSizeProperty.set(getNoteFontSize());
    }

    public static synchronized SettingsService getInstance() {
        if (instance == null) {
            instance = new SettingsService();
        }
        return instance;
    }

    private Path resolveSettingsPath() {
        try {
            System.out.println("[Settings] Resolving settings path...");

            // 尝试使用Gluon Attach StorageService (Android/iOS)
            Optional<File> privateStorage = StorageService.create().flatMap(StorageService::getPrivateStorage);

            if (privateStorage.isPresent()) {
                Path settingsPath = privateStorage.get().toPath().resolve(SETTINGS_FILE);
                System.out.println("[Settings] Using Gluon private storage: " + settingsPath);
                return settingsPath;
            } else {
                System.out.println("[Settings] Gluon private storage not available, using fallback");
            }
        } catch (Exception e) {
            System.err.println("[Settings] Error accessing Gluon storage: " + e.getMessage());
            e.printStackTrace();
        }

        // 桌面环境或Gluon存储不可用时的回退方案
        Path fallbackPath = Path.of(System.getProperty("user.home"), ".keenotes", SETTINGS_FILE);
        System.out.println("[Settings] Using fallback storage: " + fallbackPath);
        return fallbackPath;
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

    public int getLocalImportServerPort() {
        String port = properties.getProperty(KEY_LOCAL_IMPORT_SERVER_PORT, "1979");
        return Integer.parseInt(port);
    }

    public void setLocalImportServerPort(int port) {
        if (port < 1 || port > 65535) {
            properties.setProperty("local.import.server.port", "1979");
        } else {
            properties.setProperty("local.import.server.port", String.valueOf(port));
        }
    }

    public boolean getCopyToClipboardOnPost() {
        return Boolean.parseBoolean(properties.getProperty(KEY_COPY_TO_CLIPBOARD, "false"));
    }

    public void setCopyToClipboardOnPost(boolean enabled) {
        properties.setProperty(KEY_COPY_TO_CLIPBOARD, String.valueOf(enabled));
    }

    public String getSearchShortcut() {
        return properties.getProperty(KEY_SEARCH_SHORTCUT, DEFAULT_SEARCH_SHORTCUT);
    }

    public void setSearchShortcut(String shortcut) {
        properties.setProperty(KEY_SEARCH_SHORTCUT, shortcut);
    }

    public String getSendShortcut() {
        return properties.getProperty(KEY_SEND_SHORTCUT, DEFAULT_SEND_SHORTCUT);
    }

    public void setSendShortcut(String shortcut) {
        properties.setProperty(KEY_SEND_SHORTCUT, shortcut);
    }

    public String getTheme() {
        return properties.getProperty(KEY_THEME, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        properties.setProperty(KEY_THEME, theme);
    }

    public boolean getShowOverviewCard() {
        return Boolean.parseBoolean(properties.getProperty(KEY_SHOW_OVERVIEW_CARD, "true"));
    }

    public void setShowOverviewCard(boolean enabled) {
        properties.setProperty(KEY_SHOW_OVERVIEW_CARD, String.valueOf(enabled));
        showOverviewCardProperty.set(enabled);
    }

    public BooleanProperty showOverviewCardProperty() {
        return showOverviewCardProperty;
    }

    public String getFirstNoteDate() {
        return properties.getProperty(KEY_FIRST_NOTE_DATE);
    }

    public void setFirstNoteDate(String date) {
        if (date == null || date.isEmpty()) {
            properties.remove(KEY_FIRST_NOTE_DATE);
        } else {
            properties.setProperty(KEY_FIRST_NOTE_DATE, date);
        }
    }

    // Note font size settings
    public int getNoteFontSize() {
        try {
            int size = Integer.parseInt(properties.getProperty(KEY_NOTE_FONT_SIZE, String.valueOf(DEFAULT_NOTE_FONT_SIZE)));
            return Math.max(MIN_NOTE_FONT_SIZE, Math.min(MAX_NOTE_FONT_SIZE, size));
        } catch (NumberFormatException e) {
            return DEFAULT_NOTE_FONT_SIZE;
        }
    }

    public void setNoteFontSize(int size) {
        int clampedSize = Math.max(MIN_NOTE_FONT_SIZE, Math.min(MAX_NOTE_FONT_SIZE, size));
        properties.setProperty(KEY_NOTE_FONT_SIZE, String.valueOf(clampedSize));
        noteFontSizeProperty.set(clampedSize);
    }

    public IntegerProperty noteFontSizeProperty() {
        return noteFontSizeProperty;
    }

    public void zoomIn() {
        int currentSize = getNoteFontSize();
        if (currentSize < MAX_NOTE_FONT_SIZE) {
            setNoteFontSize(currentSize + FONT_SIZE_STEP);
            save();
        }
    }

    public void zoomOut() {
        int currentSize = getNoteFontSize();
        if (currentSize > MIN_NOTE_FONT_SIZE) {
            setNoteFontSize(currentSize - FONT_SIZE_STEP);
            save();
        }
    }

    // Zoom shortcuts
    public String getZoomInShortcut() {
        return properties.getProperty(KEY_ZOOM_IN_SHORTCUT, DEFAULT_ZOOM_IN_SHORTCUT);
    }

    public void setZoomInShortcut(String shortcut) {
        properties.setProperty(KEY_ZOOM_IN_SHORTCUT, shortcut);
    }

    public String getZoomOutShortcut() {
        return properties.getProperty(KEY_ZOOM_OUT_SHORTCUT, DEFAULT_ZOOM_OUT_SHORTCUT);
    }

    public void setZoomOutShortcut(String shortcut) {
        properties.setProperty(KEY_ZOOM_OUT_SHORTCUT, shortcut);
    }
}
