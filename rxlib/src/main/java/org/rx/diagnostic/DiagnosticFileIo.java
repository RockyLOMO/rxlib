package org.rx.diagnostic;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

@Slf4j
public final class DiagnosticFileIo {
    private DiagnosticFileIo() {
    }

    public static void recordRead(File file, long bytes, long elapsedNanos) {
        if (file != null && isEnabled()) {
            try {
                recordRead(file.getAbsolutePath(), bytes, elapsedNanos);
            } catch (Throwable e) {
                log.warn("diagnostic file io read record failed, bytes={}", bytes, e);
                // Diagnostic failures must never affect business execution.
            }
        }
    }

    public static void recordRead(String path, long bytes, long elapsedNanos) {
        record(path, DiagnosticFileOperation.READ, bytes, elapsedNanos);
    }

    public static void recordWrite(File file, long bytes, long elapsedNanos) {
        if (file != null && isEnabled()) {
            try {
                recordWrite(file.getAbsolutePath(), bytes, elapsedNanos);
            } catch (Throwable e) {
                log.warn("diagnostic file io write record failed, bytes={}", bytes, e);
                // Diagnostic failures must never affect business execution.
            }
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
        try {
            monitor.recordFileIo(path, operation, bytes, elapsedNanos);
        } catch (Throwable e) {
            log.warn("diagnostic file io record failed, path={}, operation={}, bytes={}", path, operation, bytes, e);
            // Diagnostic failures must never affect business execution.
        }
    }
}

