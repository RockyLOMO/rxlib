package org.rx.diagnostic;

/**
 * Lightweight metric bridge without stack collection.
 */
public final class DiagnosticMetrics {
    private static final ThreadLocal<Boolean> RECORDING = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<Boolean>();

    private DiagnosticMetrics() {
    }

    public static boolean isEnabled() {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        return monitor != null && monitor.isRunning()
                && !Boolean.TRUE.equals(RECORDING.get())
                && !Boolean.TRUE.equals(SUPPRESSED.get());
    }

    public static void record(String name, double value, String tags) {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        if (monitor == null || !monitor.isRunning()
                || Boolean.TRUE.equals(RECORDING.get())
                || Boolean.TRUE.equals(SUPPRESSED.get())) {
            return;
        }
        RECORDING.set(true);
        try {
            monitor.getStore().recordMetric(new DiagnosticMetric(System.currentTimeMillis(), name, value, tags, null));
        } catch (Throwable ignored) {
            // Metrics must never affect business execution.
        } finally {
            RECORDING.remove();
        }
    }

    public static boolean enterSuppressed() {
        boolean old = Boolean.TRUE.equals(SUPPRESSED.get());
        SUPPRESSED.set(true);
        return old;
    }

    public static void exitSuppressed(boolean old) {
        if (old) {
            SUPPRESSED.set(true);
        } else {
            SUPPRESSED.remove();
        }
    }
}
