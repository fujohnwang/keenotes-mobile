package cn.keevol.keenotes.mobilefx;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
/**
 * Application-wide executors for UI-related background work.
 * DB reads for list rendering use a single thread to avoid SQLite lock contention storms.
 */
public final class AppExecutors {

    private static final ExecutorService UI_DB = Executors.newSingleThreadExecutor(namedDaemon("ui-db"));
    private static final ExecutorService MEDIA = Executors.newSingleThreadExecutor(namedDaemon("media"));

    private AppExecutors() {
    }

    public static ExecutorService uiDb() {
        return UI_DB;
    }

    public static <T> Future<T> submitUiDb(Callable<T> task) {
        return UI_DB.submit(task);
    }

    public static void runUiDb(Runnable task) {
        UI_DB.execute(task);
    }

    public static ExecutorService media() {
        return MEDIA;
    }

    public static void shutdown() {
        UI_DB.shutdownNow();
        MEDIA.shutdownNow();
    }

    private static ThreadFactory namedDaemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
