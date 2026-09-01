package cn.keevol.keenotes.mobilefx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Builds bounded, content-free runtime snapshots for freeze diagnosis. */
final class RuntimeDiagnostics {

    private static final int MAX_STACK_FRAMES_PER_THREAD = 48;

    private RuntimeDiagnostics() {
    }

    static String buildThreadSnapshot(String reason) {
        Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        List<Map.Entry<Thread, StackTraceElement[]>> entries = new ArrayList<>(allStacks.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<Thread, StackTraceElement[]> entry) -> entry.getKey().getName())
                .thenComparingLong(entry -> entry.getKey().threadId()));

        StringBuilder snapshot = new StringBuilder(16_384);
        snapshot.append("=== Thread snapshot: ").append(reason).append(" ===")
                .append(System.lineSeparator());
        snapshot.append("capturedAt=").append(java.time.Instant.now())
                .append(" threadCount=").append(entries.size())
                .append(System.lineSeparator());

        for (Map.Entry<Thread, StackTraceElement[]> entry : entries) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            snapshot.append('"').append(thread.getName()).append('"')
                    .append(" id=").append(thread.threadId())
                    .append(" state=").append(thread.getState())
                    .append(" daemon=").append(thread.isDaemon())
                    .append(System.lineSeparator());

            int frameCount = Math.min(stack.length, MAX_STACK_FRAMES_PER_THREAD);
            for (int i = 0; i < frameCount; i++) {
                snapshot.append("    at ").append(stack[i]).append(System.lineSeparator());
            }
            if (stack.length > frameCount) {
                snapshot.append("    ... ").append(stack.length - frameCount).append(" more")
                        .append(System.lineSeparator());
            }
        }
        return snapshot.toString();
    }

    static void logThreadSnapshot(Logger logger, String reason) {
        try {
            logger.warning(buildThreadSnapshot(reason));
        } catch (Throwable error) {
            logger.log(Level.SEVERE, "Failed to capture thread snapshot for reason=" + reason, error);
        }
    }
}
