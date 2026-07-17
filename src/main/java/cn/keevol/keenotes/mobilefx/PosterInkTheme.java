package cn.keevol.keenotes.mobilefx;

/**
 * Ink wash themes for note posters. Mirrors the iOS poster theme choices.
 */
public enum PosterInkTheme {
    MEI("PosterInkMei", "梅", "/poster/ink/PosterInkMei.png"),
    LAN("PosterInkLan", "兰", "/poster/ink/PosterInkLan.png"),
    ZHU("PosterInkZhu", "竹", "/poster/ink/PosterInkZhu.png"),
    JU("PosterInkJu", "菊", "/poster/ink/PosterInkJu.png");

    private static final PosterInkTheme[] VALUES = values();

    private final String assetName;
    private final String label;
    private final String resourcePath;

    PosterInkTheme(String assetName, String label, String resourcePath) {
        this.assetName = assetName;
        this.label = label;
        this.resourcePath = resourcePath;
    }

    public String getAssetName() {
        return assetName;
    }

    public String getLabel() {
        return label;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public static PosterInkTheme stableForNoteId(long noteId) {
        int index = (int) Math.floorMod(noteId, VALUES.length);
        return VALUES[index];
    }

    public PosterInkTheme next() {
        int nextIndex = (ordinal() + 1) % VALUES.length;
        return VALUES[nextIndex];
    }

    public float opacityFor(int contentLength) {
        if (contentLength > 900) {
            return 0.07f;
        }
        if (contentLength > 500) {
            return 0.09f;
        }
        if (contentLength > 220) {
            return 0.10f;
        }
        return 0.11f;
    }
}
