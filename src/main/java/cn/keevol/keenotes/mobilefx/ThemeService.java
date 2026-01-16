package cn.keevol.keenotes.mobilefx;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Service for managing application theme (Dark/Light mode).
 * Provides reactive property for theme changes that UI components can listen to.
 */
public class ThemeService {
    
    public enum Theme {
        DARK("dark"),
        LIGHT("light");
        
        private final String value;
        
        Theme(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Theme fromString(String value) {
            for (Theme theme : values()) {
                if (theme.value.equalsIgnoreCase(value)) {
                    return theme;
                }
            }
            return DARK; // Default
        }
    }
    
    private static ThemeService instance;
    private final ObjectProperty<Theme> currentTheme = new SimpleObjectProperty<>(Theme.DARK);
    
    private ThemeService() {
        // Load theme from settings
        String savedTheme = SettingsService.getInstance().getTheme();
        currentTheme.set(Theme.fromString(savedTheme));
    }
    
    public static synchronized ThemeService getInstance() {
        if (instance == null) {
            instance = new ThemeService();
        }
        return instance;
    }
    
    /**
     * Get the reactive property for current theme.
     * UI components can listen to this property to react to theme changes.
     */
    public ObjectProperty<Theme> currentThemeProperty() {
        return currentTheme;
    }
    
    /**
     * Get current theme
     */
    public Theme getCurrentTheme() {
        return currentTheme.get();
    }
    
    /**
     * Set current theme and notify all listeners
     */
    public void setTheme(Theme theme) {
        if (theme != null && theme != currentTheme.get()) {
            currentTheme.set(theme);
            // Save to settings
            SettingsService.getInstance().setTheme(theme.getValue());
            SettingsService.getInstance().save();
        }
    }
    
    /**
     * Check if current theme is dark
     */
    public boolean isDarkTheme() {
        return currentTheme.get() == Theme.DARK;
    }
    
    /**
     * Check if current theme is light
     */
    public boolean isLightTheme() {
        return currentTheme.get() == Theme.LIGHT;
    }
}
