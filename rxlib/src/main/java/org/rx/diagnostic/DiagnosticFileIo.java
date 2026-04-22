package org.rx.diagnostic;

import java.io.File;

public final class DiagnosticFileIo {
    private DiagnosticFileIo() {
    }

    public static void recordRead(File file, long bytes, long elapsedNanos) {
        if (file != null && isEnabled()) {
            recordRead(file.getAbsolutePath(), bytes, elapsedNanos);
        }
    }

    public static void recordRead(String path, long bytes, long elapsedNanos) {
        record(path, DiagnosticFileOperation.READ, bytes, elapsedNanos);
    }

    public static void recordWrite(File file, long bytes, long elapsedNanos) {
        if (file != null && isEnabled()) {
            recordWrite(file.getAbsolutePath(), bytes, elapsedNanos);
        }
    }

    public static void recordWrite(String path, long bytes, long elapsedNanos) {
        record(path, DiagnosticFileOperation.WRITE, bytes, elapsedNanos);
    }

    public static long begin() {
        return isEnabled() ? System.nanoTime() : 0L;
    }

    public static long elapsedNanos(long beginNanos) {
        if (beginNanos <= 0L) {
            return 0L;
        }
        return System.nanoTime() - beginNanos;
    }

    public static boolean isEnabled() {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        return monitor != null && monitor.isRunning();
    }

    private static void record(String path, DiagnosticFileOperation operation, long bytes, long elapsedNanos) {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        if (monitor == null || path == null) {
            return;
        }
        monitor.recordFileIo(path, operation, bytes, elapsedNanos);
    }
}

