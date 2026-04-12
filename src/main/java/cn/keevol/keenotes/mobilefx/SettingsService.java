package cn.keevol.keenotes.mobilefx;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.text.Font;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String KEY_NOTE_FONT_FAMILY = "note.font.family";
    private static final String KEY_ZOOM_IN_SHORTCUT = "shortcut.zoom.in";
    private static final String KEY_TAKE_NOTE_SHORTCUT = "shortcut.take.note";
    private static final String KEY_ZOOM_OUT_SHORTCUT = "shortcut.zoom.out";
    private static final String KEY_LOCAL_IMPORT_SERVER_PORT = "local.import.server.port";
    private static final String KEY_MCP_SERVER_PORT = "mcp.server.port";
    private static final String KEY_MCP_SERVER_ENABLED = "mcp.server.enabled";
    private static final String KEY_HIDDEN_MESSAGE = "hidden.message";
    private static final String KEY_SHOW_SYNC_CHANNEL_STATUS = "show.sync.channel.status";
    private static final String KEY_SHOW_ON_THIS_DAY_IN_YEARS_PAST = "show.on.this.day.in.years.past";

    private static final int DEFAULT_REVIEW_DAYS = 7;
    private static final String DEFAULT_TAKE_NOTE_SHORTCUT = "Alt+T";
    private static final String DEFAULT_SEARCH_SHORTCUT = "Alt+Shift+S";
    private static final String DEFAULT_SEND_SHORTCUT = "Alt+Enter";
    private static final String DEFAULT_THEME = "dark";
    private static final int DEFAULT_NOTE_FONT_SIZE = 14;
    private static final String DEFAULT_NOTE_FONT_FAMILY = "MiSans";
    private static final String FALLBACK_NOTE_FONT_FAMILY = "System";
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
    private final StringProperty noteFontFamilyProperty = new SimpleStringProperty(DEFAULT_NOTE_FONT_FAMILY);
    private final BooleanProperty showSyncChannelStatusProperty = new SimpleBooleanProperty(false);
    private final BooleanProperty showOnThisDayInYearsPastProperty = new SimpleBooleanProperty(true);

    private SettingsService() {
        properties = new Properties();
        settingsPath = resolveSettingsPath();
        loadSettings();
        // Initialize property from loaded settings
        showOverviewCardProperty.set(getShowOverviewCard());
        noteFontSizeProperty.set(getNoteFontSize());
        noteFontFamilyProperty.set(getEffectiveNoteFontFamily());
        showSyncChannelStatusProperty.set(getShowSyncChannelStatus());
        showOnThisDayInYearsPastProperty.set(getShowOnThisDayInYearsPast());
    }

    public static synchronized SettingsService getInstance() {
        if (instance == null) {
            instance = new SettingsService();
        }
        return instance;
    }

    private Path resolveSettingsPath() {
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
        return properties.getProperty(KEY_ENDPOINT_URL, "https://kns.afoo.me");
    }

    public void setEndpointUrl(String url) {
        properties.setProperty(KEY_ENDPOINT_URL, url);
    }

    public String getToken() {
        String value = properties.getProperty(KEY_TOKEN, "");
        return CryptoHelper.decrypt(value);
    }

    public void setToken(String token) {
        if (token == null || token.isEmpty()) {
            properties.remove(KEY_TOKEN);
        } else {
            properties.setProperty(KEY_TOKEN, CryptoHelper.encrypt(token));
        }
    }

    public boolean isConfigured() {
        // 检查所有必填字段是否都已配置（不为空）
        // 使用 properties.getProperty() 而不是 getter，避免默认值干扰判断
        String endpoint = properties.getProperty(KEY_ENDPOINT_URL);
        String token = properties.getProperty(KEY_TOKEN);
        String encryptionPassword = properties.getProperty(KEY_ENCRYPTION_PASSWORD);
        
        // 只要有任何一个必填字段为空，就认为未配置
        // 包括：endpoint, token, encryptionPassword（三个都是必填）
        return !(endpoint == null || endpoint.isBlank() || 
                 token == null || token.isBlank() ||
                 encryptionPassword == null || encryptionPassword.isBlank());
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
        String value = properties.getProperty(KEY_ENCRYPTION_PASSWORD, "");
        return CryptoHelper.decrypt(value);
    }

    public void setEncryptionPassword(String password) {
        if (password == null || password.isEmpty()) {
            properties.remove(KEY_ENCRYPTION_PASSWORD);
        } else {
            properties.setProperty(KEY_ENCRYPTION_PASSWORD, CryptoHelper.encrypt(password));
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



    public String getHiddenMessage() {
        return properties.getProperty(KEY_HIDDEN_MESSAGE, "");
    }

    public void setHiddenMessage(String message) {
        properties.setProperty(KEY_HIDDEN_MESSAGE, message != null ? message : "");
    }

    public String getTakeNoteShortcut() {
        return properties.getProperty(KEY_TAKE_NOTE_SHORTCUT, DEFAULT_TAKE_NOTE_SHORTCUT);
    }

    public void setTakeNoteShortcut(String shortcut) {
        properties.setProperty(KEY_TAKE_NOTE_SHORTCUT, shortcut);
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

    public boolean getShowOnThisDayInYearsPast() {
        return Boolean.parseBoolean(properties.getProperty(KEY_SHOW_ON_THIS_DAY_IN_YEARS_PAST, "true"));
    }

    public void setShowOnThisDayInYearsPast(boolean enabled) {
        properties.setProperty(KEY_SHOW_ON_THIS_DAY_IN_YEARS_PAST, String.valueOf(enabled));
        showOnThisDayInYearsPastProperty.set(enabled);
    }

    public BooleanProperty showOnThisDayInYearsPastProperty() {
        return showOnThisDayInYearsPastProperty;
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

    public String getNoteFontFamily() {
        String fontFamily = properties.getProperty(KEY_NOTE_FONT_FAMILY, DEFAULT_NOTE_FONT_FAMILY);
        return fontFamily == null || fontFamily.isBlank() ? DEFAULT_NOTE_FONT_FAMILY : fontFamily;
    }

    public String getEffectiveNoteFontFamily() {
        return resolveAvailableFontFamily(getNoteFontFamily());
    }

    public void setNoteFontFamily(String fontFamily) {
        String normalizedFontFamily = fontFamily == null ? "" : fontFamily.trim();
        if (normalizedFontFamily.isEmpty()) {
            properties.remove(KEY_NOTE_FONT_FAMILY);
        } else {
            properties.setProperty(KEY_NOTE_FONT_FAMILY, normalizedFontFamily);
        }
        noteFontFamilyProperty.set(getEffectiveNoteFontFamily());
    }

    public StringProperty noteFontFamilyProperty() {
        return noteFontFamilyProperty;
    }

    public List<String> getAvailableNoteFontFamilies() {
        return loadAvailableFontFamilies();
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

    // MCP Server settings
    public int getMcpServerPort() {
        String port = properties.getProperty(KEY_MCP_SERVER_PORT, "1999");
        return Integer.parseInt(port);
    }

    public void setMcpServerPort(int port) {
        if (port < 1 || port > 65535) {
            properties.setProperty(KEY_MCP_SERVER_PORT, "1999");
        } else {
            properties.setProperty(KEY_MCP_SERVER_PORT, String.valueOf(port));
        }
    }

    public boolean isMcpServerEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_MCP_SERVER_ENABLED, "true"));
    }

    public void setMcpServerEnabled(boolean enabled) {
        properties.setProperty(KEY_MCP_SERVER_ENABLED, String.valueOf(enabled));
    }

    // Sync Channel Status visibility settings
    public boolean getShowSyncChannelStatus() {
        return Boolean.parseBoolean(properties.getProperty(KEY_SHOW_SYNC_CHANNEL_STATUS, "false"));
    }

    public void setShowSyncChannelStatus(boolean enabled) {
        properties.setProperty(KEY_SHOW_SYNC_CHANNEL_STATUS, String.valueOf(enabled));
        showSyncChannelStatusProperty.set(enabled);
    }

    public BooleanProperty showSyncChannelStatusProperty() {
        return showSyncChannelStatusProperty;
    }

    private String resolveAvailableFontFamily(String requestedFontFamily) {
        List<String> availableFontFamilies = loadAvailableFontFamilies();

        String requestedMatch = findFontFamilyIgnoreCase(availableFontFamilies, requestedFontFamily);
        if (requestedMatch != null) {
            return requestedMatch;
        }

        String defaultMatch = findFontFamilyIgnoreCase(availableFontFamilies, DEFAULT_NOTE_FONT_FAMILY);
        if (defaultMatch != null) {
            return defaultMatch;
        }

        String fallbackMatch = findFontFamilyIgnoreCase(availableFontFamilies, FALLBACK_NOTE_FONT_FAMILY);
        if (fallbackMatch != null) {
            return fallbackMatch;
        }

        return availableFontFamilies.isEmpty() ? FALLBACK_NOTE_FONT_FAMILY : availableFontFamilies.get(0);
    }

    private String findFontFamilyIgnoreCase(List<String> fontFamilies, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        for (String fontFamily : fontFamilies) {
            if (fontFamily.equalsIgnoreCase(candidate)) {
                return fontFamily;
            }
        }
        return null;
    }

    private List<String> loadAvailableFontFamilies() {
        try {
            LinkedHashSet<String> uniqueFontFamilies = new LinkedHashSet<>(Font.getFamilies());
            List<String> fontFamilies = new ArrayList<>(uniqueFontFamilies);
            fontFamilies.sort(String.CASE_INSENSITIVE_ORDER);
            return fontFamilies;
        } catch (Exception e) {
            List<String> fallbackFontFamilies = new ArrayList<>();
            fallbackFontFamilies.add(DEFAULT_NOTE_FONT_FAMILY);
            fallbackFontFamilies.add(FALLBACK_NOTE_FONT_FAMILY);
            return fallbackFontFamilies;
        }
    }
}
