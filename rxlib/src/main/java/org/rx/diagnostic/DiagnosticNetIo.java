package org.rx.diagnostic;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class DiagnosticNetIo {
    private DiagnosticNetIo() {
    }

    public static void recordInbound(String endpoint, long bytes) {
        record(endpoint, DiagnosticNetOperation.INBOUND, bytes);
    }

    public static void recordOutbound(String endpoint, long bytes) {
        record(endpoint, DiagnosticNetOperation.OUTBOUND, bytes);
    }

    public static boolean isEnabled() {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        return monitor != null && monitor.isNetIoSamplingEnabled();
    }

    private static void record(String endpoint, DiagnosticNetOperation operation, long bytes) {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        if (monitor == null || endpoint == null || bytes <= 0L) {
            return;
        }
        try {
            monitor.recordNetIo(endpoint, operation, bytes);
        } catch (Throwable e) {
            log.warn("diagnostic net io record failed, endpoint={}, operation={}, bytes={}", endpoint, operation, bytes, e);
            // Diagnostic failures must never affect business execution.
        }
    }
}
