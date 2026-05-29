package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Coordinates cancellable UI data loads: one in-flight task per slot, generation tokens for stale callbacks,
 * and debounced scheduling for rapid navigation.
 */
final class UiLoadCoordinator {

    private static final Logger logger = AppLogger.getLogger(UiLoadCoordinator.class);

    private final Map<String, AtomicInteger> generations = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Future<?>>> inflight = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> debounceTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-load-debounce");
        t.setDaemon(true);
        return t;
    });

    void debounce(String key, long delayMs, Runnable action) {
        ScheduledFuture<?> previous = debounceTasks.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        debounceTasks.put(key, debounceScheduler.schedule(() -> {
            debounceTasks.remove(key);
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        }, delayMs, TimeUnit.MILLISECONDS));
    }

    void scheduleDelayed(String key, long delayMs, Runnable action) {
        debounce(key, delayMs, action);
    }

    <T> void submit(String slot, Callable<T> background, Consumer<T> onSuccess, Consumer<Exception> onError) {
        int generation = generations.computeIfAbsent(slot, s -> new AtomicInteger(0)).incrementAndGet();
        cancelFuture(slot);

        Future<?> future = AppExecutors.submitUiDb(() -> {
            try {
                T result = background.call();
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                int currentGeneration = generations.get(slot).get();
                if (generation != currentGeneration) {
                    logger.fine("Stale ui-db result ignored for slot=" + slot + " gen=" + generation);
                    return null;
                }
                Platform.runLater(() -> {
                    if (generation != generations.get(slot).get()) {
                        return;
                    }
                    onSuccess.accept(result);
                });
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                int currentGeneration = generations.get(slot).get();
                if (generation != currentGeneration) {
                    return null;
                }
                Platform.runLater(() -> {
                    if (generation != generations.get(slot).get()) {
                        return;
                    }
                    if (onError != null) {
                        onError.accept(e);
                    }
                });
            }
            return null;
        });
        inflight.computeIfAbsent(slot, s -> new AtomicReference<>()).set(future);
    }

    void cancelAll() {
        debounceTasks.values().forEach(f -> f.cancel(false));
        debounceTasks.clear();
        generations.values().forEach(gen -> gen.incrementAndGet());
        inflight.values().forEach(this::cancelReference);
        inflight.clear();
    }

    private void cancelFuture(String slot) {
        AtomicReference<Future<?>> ref = inflight.get(slot);
        if (ref != null) {
            cancelReference(ref);
        }
    }

    private void cancelReference(AtomicReference<Future<?>> ref) {
        Future<?> future = ref.getAndSet(null);
        if (future != null) {
            future.cancel(true);
        }
    }

    void shutdown() {
        cancelAll();
        debounceScheduler.shutdownNow();
    }
}
