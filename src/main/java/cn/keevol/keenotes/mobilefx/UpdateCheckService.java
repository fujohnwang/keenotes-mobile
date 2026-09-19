package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.generated.BuildInfo;
import io.vertx.core.json.JsonObject;
import javafx.animation.PauseTransition;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.util.Duration;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Background update check. Create, start and close on the JavaFX Application Thread. */
public final class UpdateCheckService implements AutoCloseable {
    private static final Logger logger = AppLogger.getLogger(UpdateCheckService.class);
    private static final String VERSION_API_URL = "https://kns.afoo.me/version/latest";
    private static final int MAX_ATTEMPTS = 4;

    private final String currentVersion;
    private final OkHttpClient httpClient;
    private final Duration retryDelay;
    private final PauseTransition delay;
    private final Service<UpdateInfo> check;
    private UpdateListener listener;
    private Call activeCall;
    private int attempts;
    private boolean started;
    private boolean closed;

    public UpdateCheckService() {
        this(BuildInfo.VERSION, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build(), Duration.seconds(3), Duration.seconds(30));
    }

    // Takes ownership of the client; injectable delays keep retry/cancellation tests local and fast.
    UpdateCheckService(String currentVersion, OkHttpClient httpClient,
                       Duration initialDelay, Duration retryDelay) {
        this.currentVersion = currentVersion;
        this.httpClient = httpClient;
        this.retryDelay = retryDelay;
        delay = new PauseTransition(initialDelay);
        check = new Service<>() {
            @Override
            protected Task<UpdateInfo> createTask() {
                // Created on the FX thread so close() can cancel even a not-yet-executed call.
                Call call = httpClient.newCall(new Request.Builder().url(VERSION_API_URL).build());
                activeCall = call;
                return new Task<>() {
                    @Override
                    protected UpdateInfo call() throws Exception {
                        try (Response response = call.execute()) {
                            if (!response.isSuccessful()) {
                                throw new IOException("HTTP " + response.code());
                            }
                            if (response.body() == null) {
                                throw new IOException("Empty update response");
                            }
                            JsonObject json = new JsonObject(response.body().string());
                            String version = json.getString("version");
                            String url = json.getString("url");
                            if (version == null || version.isBlank() || url == null || url.isBlank()) {
                                throw new IOException("Update response is missing version or URL");
                            }
                            return isNewerVersion(version, currentVersion) ? new UpdateInfo(version, url) : null;
                        }
                    }
                };
            }
        };
        delay.setOnFinished(event -> {
            if (!closed) {
                attempts++;
                logger.info("Checking for updates: current=" + currentVersion + ", attempt=" + attempts);
                check.restart();
            }
        });
        check.setOnSucceeded(event -> {
            activeCall = null;
            if (closed) {
                return;
            }
            UpdateInfo update = check.getValue();
            logger.info(update == null ? "Already on latest version" : "New version available: " + update.version());
            if (update != null && listener != null) {
                listener.onUpdateAvailable(update.version(), update.url());
            }
        });
        check.setOnFailed(event -> {
            activeCall = null;
            if (closed) {
                return;
            }
            boolean retry = attempts < MAX_ATTEMPTS;
            logger.warning("Update check failed (attempt " + attempts + "): "
                    + check.getException().getMessage()
                    + (retry ? "; retrying in " + retryDelay.toSeconds() + " seconds" : "; retry limit reached"));
            if (retry) {
                delay.setDuration(retryDelay);
                delay.playFromStart();
            }
        });
    }

    /** Schedule one check, with bounded retries on failure. Repeated starts are ignored. */
    public void checkForUpdates() {
        if (closed || started) {
            return;
        }
        started = true;
        if ("dev".equals(currentVersion)) {
            logger.info("Skipping update check for dev build");
            return;
        }
        delay.playFromStart();
    }

    private static boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("\\.", -1);
        String[] currentParts = current.split("\\.", -1);
        // Invalid responses must fail the Task and trigger retries, not masquerade as "up to date".
        int[] latestNumbers = java.util.Arrays.stream(latestParts).mapToInt(Integer::parseInt).toArray();
        int[] currentNumbers = java.util.Arrays.stream(currentParts).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < Math.max(latestNumbers.length, currentNumbers.length); i++) {
            int latestPart = i < latestNumbers.length ? latestNumbers[i] : 0;
            int currentPart = i < currentNumbers.length ? currentNumbers[i] : 0;
            if (latestPart != currentPart) {
                return latestPart > currentPart;
            }
        }
        return false;
    }

    public void setUpdateListener(UpdateListener listener) {
        this.listener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        delay.stop();
        delay.setOnFinished(null);
        check.cancel();
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
        check.setOnSucceeded(null);
        check.setOnFailed(null);
        listener = null;
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
        httpClient.dispatcher().executorService().shutdown();
    }

    private record UpdateInfo(String version, String url) {}

    public interface UpdateListener {
        void onUpdateAvailable(String version, String downloadUrl);
    }
}
