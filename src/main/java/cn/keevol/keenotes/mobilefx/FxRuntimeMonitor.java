package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a blocked JavaFX event queue and a requested foreground pulse that
 * never completes. Scheduler gaps are treated as App Nap/system sleep and
 * suppressed instead of being reported as UI freezes.
 */
final class FxRuntimeMonitor {

    private static final Logger logger = AppLogger.getLogger(FxRuntimeMonitor.class);
    private static final long CHECK_INTERVAL_SECONDS = 2;
    private static final long STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final long SCHEDULER_GAP_NANOS = TimeUnit.SECONDS.toNanos(6);
    private static final long THREAD_DUMP_COOLDOWN_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Stage stage;
    private final Runnable pulseListener;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong pendingQueuePingNanos = new AtomicLong(0);
    private final AtomicLong pendingPulseRequestNanos = new AtomicLong(0);
    private final AtomicLong lastSchedulerCheckNanos = new AtomicLong(0);
    private final AtomicLong lastThreadDumpNanos = new AtomicLong(0);
    private final AtomicBoolean queueStallReported = new AtomicBoolean(false);
    private final AtomicBoolean pulseStallReported = new AtomicBoolean(false);

    private final ChangeListener<Boolean> focusedListener;
    private final ChangeListener<Boolean> iconifiedListener;
    private final ChangeListener<Boolean> showingListener;
    private final EventHandler<WindowEvent> closeRequestHandler;

    private volatile boolean queueMonitoringEnabled;
    private volatile boolean pulseMonitoringEnabled;
    private volatile boolean stopped;
    private volatile String lastWindowState = "window-state-unavailable";
    private ScheduledFuture<?> healthCheckTask;
    private boolean started;

    FxRuntimeMonitor(Stage stage) {
        this.stage = stage;
        this.pulseListener = this::recordPulseSafely;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fx-watchdog");
            thread.setDaemon(true);
            return thread;
        });

        focusedListener = (observable, oldValue, newValue) -> {
            logger.info("Window focused: " + oldValue + " -> " + newValue);
            updateMonitoringState();
        };
        iconifiedListener = (observable, oldValue, newValue) -> {
            logger.info("Window iconified: " + oldValue + " -> " + newValue);
            updateMonitoringState();
        };
        showingListener = (observable, oldValue, newValue) -> {
            logger.info("Window showing: " + oldValue + " -> " + newValue);
            updateMonitoringState();
        };
        closeRequestHandler = event -> logger.info("Window close requested");
    }

    void start() {
        requireFxThread();
        if (started) {
            return;
        }
        started = true;
        stage.focusedProperty().addListener(focusedListener);
        stage.iconifiedProperty().addListener(iconifiedListener);
        stage.showingProperty().addListener(showingListener);
        stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequestHandler);
        stage.getScene().addPostLayoutPulseListener(pulseListener);

        long now = System.nanoTime();
        lastSchedulerCheckNanos.set(now);
        logWindowState("monitor-start");
        updateMonitoringState();
    }

    void stop() {
        requireFxThread();
        if (stopped) {
            return;
        }
        stopped = true;
        queueMonitoringEnabled = false;
        pulseMonitoringEnabled = false;
        pendingQueuePingNanos.set(0);
        pendingPulseRequestNanos.set(0);
        stopHealthChecks();
        stage.focusedProperty().removeListener(focusedListener);
        stage.iconifiedProperty().removeListener(iconifiedListener);
        stage.showingProperty().removeListener(showingListener);
        stage.removeEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequestHandler);
        stage.getScene().removePostLayoutPulseListener(pulseListener);
        scheduler.shutdownNow();
        logger.info("FX runtime monitor stopped");
    }

    private void updateMonitoringState() {
        boolean foreground = stage.isShowing() && !stage.isIconified() && stage.isFocused();
        queueMonitoringEnabled = foreground;
        pulseMonitoringEnabled = foreground;

        long now = System.nanoTime();
        if (foreground) {
            startHealthChecks(now);
        } else {
            stopHealthChecks();
            pulseStallReported.set(false);
            pendingQueuePingNanos.set(0);
            pendingPulseRequestNanos.set(0);
            queueStallReported.set(false);
        }
        logWindowState("state-change");
    }

    private void startHealthChecks(long now) {
        if (healthCheckTask != null && !healthCheckTask.isDone()) {
            return;
        }
        lastSchedulerCheckNanos.set(now);
        healthCheckTask = scheduler.scheduleAtFixedRate(this::checkHealthSafely,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHealthChecks() {
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
            healthCheckTask = null;
        }
    }

    private void recordPulseSafely() {
        try {
            pendingPulseRequestNanos.set(0);
            pulseStallReported.set(false);
        } catch (Throwable error) {
            stage.getScene().removePostLayoutPulseListener(pulseListener);
            logger.log(Level.SEVERE, "Pulse listener failed and was removed", error);
        }
    }

    private void checkHealthSafely() {
        try {
            checkHealth();
        } catch (Throwable error) {
            logger.log(Level.SEVERE, "FX watchdog check failed", error);
        }
    }

    private void checkHealth() {
        if (stopped) {
            return;
        }

        long now = System.nanoTime();
        long previousCheck = lastSchedulerCheckNanos.getAndSet(now);
        if (previousCheck != 0 && now - previousCheck > SCHEDULER_GAP_NANOS) {
            pendingQueuePingNanos.set(0);
            pendingPulseRequestNanos.set(0);
            queueStallReported.set(false);
            pulseStallReported.set(false);
            logger.info("FX watchdog resumed after scheduler gap="
                    + TimeUnit.NANOSECONDS.toMillis(now - previousCheck)
                    + "ms; treating as App Nap/system sleep");
            return;
        }

        if (queueMonitoringEnabled) {
            checkFxQueue(now);
        } else {
            pendingQueuePingNanos.set(0);
        }

        if (pulseMonitoringEnabled) {
            long requestedAt = pendingPulseRequestNanos.get();
            if (requestedAt != 0) {
                long pulseDelay = now - requestedAt;
                if (pulseDelay > STALL_THRESHOLD_NANOS) {
                    reportStall(pulseStallReported, "JavaFX pulse", pulseDelay, now);
                }
            }
        } else {
            pendingPulseRequestNanos.set(0);
        }
    }

    private void checkFxQueue(long now) {
        long pendingSince = pendingQueuePingNanos.get();
        if (pendingSince == 0 && pendingQueuePingNanos.compareAndSet(0, now)) {
            try {
                Platform.runLater(() -> completeQueuePing(now));
            } catch (IllegalStateException toolkitStopped) {
                pendingQueuePingNanos.compareAndSet(now, 0);
                if (!stopped) {
                    logger.log(Level.WARNING, "Unable to enqueue FX watchdog ping", toolkitStopped);
                }
            }
            return;
        }

        if (pendingSince != 0) {
            long queueDelay = now - pendingSince;
            if (queueDelay > STALL_THRESHOLD_NANOS) {
                reportStall(queueStallReported, "JavaFX event queue", queueDelay, now);
            }
        }
    }

    private void completeQueuePing(long sentNanos) {
        if (!pendingQueuePingNanos.compareAndSet(sentNanos, 0)) {
            return;
        }
        long delay = System.nanoTime() - sentNanos;
        if (delay > STALL_THRESHOLD_NANOS) {
            reportStall(queueStallReported, "JavaFX event queue", delay, System.nanoTime());
        } else {
            queueStallReported.set(false);
        }
        requestPulseCheck();
    }

    private void requestPulseCheck() {
        if (stopped || !pulseMonitoringEnabled) {
            return;
        }
        long requestedAt = System.nanoTime();
        if (!pendingPulseRequestNanos.compareAndSet(0, requestedAt)) {
            return;
        }
        try {
            Platform.requestNextPulse();
        } catch (Throwable error) {
            pendingPulseRequestNanos.compareAndSet(requestedAt, 0);
            logger.log(Level.SEVERE, "Unable to request FX watchdog pulse", error);
        }
    }

    private void reportStall(AtomicBoolean incidentFlag, String kind, long delayNanos, long now) {
        // A scheduled check may race with a focus/iconify event. Do not report a
        // foreground-only incident after monitoring has already been suspended.
        if (!queueMonitoringEnabled && !pulseMonitoringEnabled) {
            return;
        }
        if (!incidentFlag.compareAndSet(false, true)) {
            return;
        }
        long delayMillis = TimeUnit.NANOSECONDS.toMillis(delayNanos);
        logger.warning(kind + " unresponsive for " + delayMillis + "ms; " + lastWindowState);
        maybeLogThreadSnapshot(kind + " stalled for " + delayMillis + "ms", now);
    }

    private void maybeLogThreadSnapshot(String reason, long now) {
        long previousDump = lastThreadDumpNanos.get();
        if (previousDump != 0 && now - previousDump < THREAD_DUMP_COOLDOWN_NANOS) {
            return;
        }
        if (lastThreadDumpNanos.compareAndSet(previousDump, now)) {
            RuntimeDiagnostics.logThreadSnapshot(logger, reason);
        }
    }

    private void logWindowState(String event) {
        lastWindowState = captureWindowState();
        logger.info("Window lifecycle event=" + event + " " + lastWindowState);
    }

    private String captureWindowState() {
        return "showing=" + stage.isShowing()
                + ", focused=" + stage.isFocused()
                + ", iconified=" + stage.isIconified()
                + ", maximized=" + stage.isMaximized()
                + ", size=" + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight());
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("FX runtime monitor lifecycle must run on the JavaFX Application Thread");
        }
    }
}
