package org.rx.diagnostic;

public final class DiagnosticNetIo {
    private DiagnosticNetIo() {
    }

    public static void recordInbound(String endpoint, long bytes) {
        record(endpoint, DiagnosticNetOperation.INBOUND, bytes);
    }

    public static void recordOutbound(String endpoint, long bytes) {
        record(endpoint, DiagnosticNetOperation.OUTBOUND, bytes);
    }

    private static void record(String endpoint, DiagnosticNetOperation operation, long bytes) {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        if (monitor == null || endpoint == null || bytes <= 0L) {
            return;
        }
        monitor.recordNetIo(endpoint, operation, bytes);
    }
}
