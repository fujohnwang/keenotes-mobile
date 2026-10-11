package cn.keevol.keenotes.mobilefx;

import java.awt.Color;

/** Session-local poster palettes, independent of the ink illustration. */
public enum PosterColorTheme {
    PAPER("纸间 · 暖白", 0xFDFCFB, 0x1A1816, 0x1A1816),
    KLEIN("洞见 · 克莱因蓝", 0x002FA7, 0xFFFFFF, 0xB7C9F1),
    MIDNIGHT("深思 · 深夜蓝", 0x111C30, 0xF4F6FB, 0xAEBBCC),
    PINE("沉淀 · 松墨绿", 0x123A35, 0xF2F3EB, 0xB0C5BC);

    private final String label;
    private final int backgroundRgb;
    private final int textRgb;
    private final int secondaryRgb;

    PosterColorTheme(String label, int backgroundRgb, int textRgb, int secondaryRgb) {
        this.label = label;
        this.backgroundRgb = backgroundRgb;
        this.textRgb = textRgb;
        this.secondaryRgb = secondaryRgb;
    }

    public String getLabel() { return label; }
    public String backgroundCss() { return String.format("#%06X", backgroundRgb); }
    public boolean isDark() { return this != PAPER; }
    public Color background() { return new Color(backgroundRgb); }
    public Color text() { return new Color(textRgb); }
    public Color footer() { return new Color((isDark() ? 255 : 88) << 24 | secondaryRgb, true); }
    public Color badgeText() { return isDark() ? footer() : new Color(26, 24, 22, 148); }
    public Color badgeFill() { return isDark() ? new Color(255, 255, 255, 12) : new Color(0, 0, 0, 12); }
    public Color badgeStroke() { return isDark() ? new Color(255, 255, 255, 36) : new Color(0, 0, 0, 14); }
    public Color divider() { return isDark() ? new Color(255, 255, 255, 38) : new Color(0, 0, 0, 15); }
    public Color outline() { return new Color(255, 255, 255, isDark() ? 32 : 210); }

    public PosterColorTheme offset(int delta) {
        PosterColorTheme[] themes = values();
        return themes[Math.floorMod(ordinal() + delta, themes.length)];
    }
}
