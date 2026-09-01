package cn.keevol.keenotes.mobilefx;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

/**
 * 统一日志工具，基于 JDK java.util.logging。
 * 日志同时输出到控制台和文件 (~/keenotes.log)。
 * 文件采用滚动策略：单文件最大 2MB，保留 3 个历史文件。
 */
public final class AppLogger {

    private static final String LOG_FILE_NAME = "keenotes.log";
    private static final int MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB
    private static final int MAX_FILE_COUNT = 3;

    private static volatile boolean initialized = false;
    private static final Logger ROOT = Logger.getLogger("cn.keevol.keenotes");
    private static final AtomicBoolean UNCAUGHT_HANDLER_INSTALLED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> HANDLING_UNCAUGHT = ThreadLocal.withInitial(() -> false);

    private AppLogger() {}

    /** 获取指定名称的 Logger（自动初始化） */
    public static Logger getLogger(String name) {
        ensureInitialized();
        return Logger.getLogger(name);
    }

    /** 获取指定类的 Logger（自动初始化） */
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    /** Install a process-wide last-resort exception logger before JavaFX starts. */
    public static void installGlobalUncaughtExceptionHandler() {
        ensureInitialized();
        if (UNCAUGHT_HANDLER_INSTALLED.compareAndSet(false, true)) {
            Thread.setDefaultUncaughtExceptionHandler(AppLogger::handleUncaughtException);
            ROOT.info("Global uncaught exception handler installed");
        }
    }

    /** JavaFX may use a thread-specific handler, so bind the FX thread explicitly. */
    public static void installCurrentThreadUncaughtExceptionHandler() {
        ensureInitialized();
        Thread.currentThread().setUncaughtExceptionHandler(AppLogger::handleUncaughtException);
        ROOT.info("Uncaught exception handler installed for thread=" + Thread.currentThread().getName());
    }

    private static void handleUncaughtException(Thread thread, Throwable error) {
        if (Boolean.TRUE.equals(HANDLING_UNCAUGHT.get())) {
            System.err.println(SensitiveLogRedactor.redact(
                    "[AppLogger] Recursive uncaught exception on " + thread.getName() + ": " + error));
            return;
        }

        HANDLING_UNCAUGHT.set(true);
        try {
            ensureInitialized();
            ROOT.log(Level.SEVERE, "Uncaught exception on thread=" + thread.getName()
                    + " state=" + thread.getState(), error);
            if ("JavaFX Application Thread".equals(thread.getName())) {
                RuntimeDiagnostics.logThreadSnapshot(ROOT, "uncaught FX exception");
            }
        } catch (Throwable loggingFailure) {
            System.err.println(SensitiveLogRedactor.redact(
                    "[AppLogger] Failed to log uncaught exception: " + loggingFailure));
            System.err.print(redactedStackTrace(error));
        } finally {
            HANDLING_UNCAUGHT.remove();
        }
    }

    private static synchronized void ensureInitialized() {
        if (initialized) return;

        ROOT.setLevel(Level.ALL);
        ROOT.setUseParentHandlers(false);

        // Console logging remains available even if file creation fails.
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new CompactFormatter());
        consoleHandler.setLevel(Level.ALL);
        ROOT.addHandler(consoleHandler);

        try {
            String logDir = resolveLogDir();
            String logPath = logDir + File.separator + LOG_FILE_NAME;

            FileHandler fileHandler = new FileHandler(logPath, MAX_FILE_SIZE, MAX_FILE_COUNT, true);
            fileHandler.setFormatter(new CompactFormatter());
            fileHandler.setLevel(Level.ALL);

            ROOT.addHandler(fileHandler);
            ROOT.info("AppLogger initialized, log file: " + logPath);
        } catch (Exception e) {
            System.err.println(SensitiveLogRedactor.redact(
                    "[AppLogger] Failed to initialize file logging: " + e.getMessage()));
            System.err.print(redactedStackTrace(e));
            ROOT.log(Level.WARNING, "File logging unavailable; console logging remains active", e);
        } finally {
            initialized = true; // 即使失败也标记，避免反复重试
        }
    }

    private static String resolveLogDir() {
        return System.getProperty("user.home");
    }

    private static String redactedStackTrace(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return SensitiveLogRedactor.redact(writer.toString());
    }

    /**
     * 紧凑的单行日志格式：[时间] LEVEL [线程] 类名短名 - 消息
     */
    static class CompactFormatter extends Formatter {
        private static final java.time.format.DateTimeFormatter TIME_FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            String time = java.time.LocalDateTime.now().format(TIME_FMT);
            String level = record.getLevel().getName();
            String thread = Thread.currentThread().getName();
            String source = record.getLoggerName();
            // 取最后一段作为短名
            int dot = source.lastIndexOf('.');
            if (dot >= 0) source = source.substring(dot + 1);

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(time).append("] ")
              .append(String.format("%-7s", level))
              .append(" [").append(thread).append("] ")
              .append(source).append(" - ")
              .append(formatMessage(record))
              .append(System.lineSeparator());

            if (record.getThrown() != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                record.getThrown().printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return SensitiveLogRedactor.redact(sb.toString());
        }
    }
}
