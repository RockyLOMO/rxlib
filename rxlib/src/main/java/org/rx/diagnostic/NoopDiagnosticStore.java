package org.rx.diagnostic;

public final class NoopDiagnosticStore implements DiagnosticStore {
    public static final NoopDiagnosticStore INSTANCE = new NoopDiagnosticStore();

    private NoopDiagnosticStore() {
    }

    @Override
    public void start() {
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public void recordMetric(DiagnosticMetric metric) {
    }

    @Override
    public void recordStackTrace(long stackHash, String stackTrace, long timestampMillis) {
    }

    @Override
    public void recordThreadCpu(ThreadCpuSample sample, String incidentId) {
    }

    @Override
    public void recordFileIo(long timestampMillis, String path, DiagnosticFileOperation operation, long bytes,
                             long elapsedNanos, long stackHash, String incidentId) {
    }

    @Override
    public void recordFileSize(long timestampMillis, String path, long sizeBytes, long lastModifiedMillis, String incidentId) {
    }

    @Override
    public void recordIncident(String incidentId, DiagnosticIncidentType type, DiagnosticLevel level, long startMillis,
                               long endMillis, String summary, String bundlePath) {
    }

    @Override
    public boolean flush(long timeoutMillis) {
        return true;
    }

    @Override
    public int pendingRecords() {
        return 0;
    }

    @Override
    public long droppedRecords() {
        return 0L;
    }

    @Override
    public void close() {
    }
}

