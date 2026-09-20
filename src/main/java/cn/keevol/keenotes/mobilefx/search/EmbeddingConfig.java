package cn.keevol.keenotes.mobilefx.search;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record EmbeddingConfig(boolean enabled, String baseUrl, String model, String apiKey,
                              String documentPrefix, String queryPrefix) {
    public EmbeddingConfig {
        baseUrl = value(baseUrl).strip().replaceAll("/+$", "");
        model = value(model).strip();
        apiKey = value(apiKey);
        documentPrefix = value(documentPrefix);
        queryPrefix = value(queryPrefix);
    }

    public static EmbeddingConfig disabled() { return new EmbeddingConfig(false, "", "", "", "", ""); }
    private static String value(String s) { return s == null ? "" : s; }
    public boolean configured() { return !baseUrl.isBlank() && !model.isBlank(); }
    public boolean usable() { return enabled && configured(); }

    public URI endpoint() {
        URI uri = URI.create(baseUrl + "/embeddings");
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use an HTTP(S) API base URL, for example http://localhost:11434/v1");
        }
        return uri;
    }

    public String profile() {
        return hash(baseUrl + "\n" + model + "\n" + documentPrefix.length() + ":" + documentPrefix
                + queryPrefix.length() + ":" + queryPrefix + "\ncosine-float-v1");
    }

    public static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public String toString() { return "EmbeddingConfig[enabled=" + enabled + ", profile=" + profile() + "]"; }
}
