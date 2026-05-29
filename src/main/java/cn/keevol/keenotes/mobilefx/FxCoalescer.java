package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces multiple {@link Platform#runLater} requests into at most one pending FX runnable.
 * Used for high-frequency UI updates (e.g. sync indicator) to avoid flooding the FX queue.
 */
final class FxCoalescer {

    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile Runnable pending;

    void runLater(Runnable action) {
        pending = action;
        if (scheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                scheduled.set(false);
                Runnable task = pending;
                if (task != null) {
                    task.run();
                }
            });
        }
    }
}
