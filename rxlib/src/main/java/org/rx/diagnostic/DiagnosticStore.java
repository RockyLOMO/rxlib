package org.rx.diagnostic;

public interface DiagnosticStore extends AutoCloseable {
    void start();

    boolean isRunning();

    void recordMetric(DiagnosticMetric metric);

    void recordStackTrace(long stackHash, String stackTrace, long timestampMillis);

    void recordThreadCpu(ThreadCpuSample sample, String incidentId);

    void recordFileIo(long timestampMillis, String path, DiagnosticFileOperation operation, long bytes,
                      long elapsedNanos, long stackHash, String incidentId);

    void recordFileSize(long timestampMillis, String path, long sizeBytes, long lastModifiedMillis, String incidentId);

    void recordIncident(String incidentId, DiagnosticIncidentType type, DiagnosticLevel level, long startMillis,
                        long endMillis, String summary, String bundlePath);

    boolean flush(long timeoutMillis) throws InterruptedException;

    int pendingRecords();

    long droppedRecords();

    @Override
    void close();
}

