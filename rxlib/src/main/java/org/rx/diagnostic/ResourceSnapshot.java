package org.rx.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.List;

@Getter
public final class ResourceSnapshot {
    @Getter
    @RequiredArgsConstructor
    public static final class DiskUsage {
        private final String path;
        private final long totalBytes;
        private final long freeBytes;

        public long getUsedBytes() {
            return Math.max(0L, totalBytes - freeBytes);
        }

        public double getFreePercent() {
            return totalBytes <= 0L ? 100D : (double) freeBytes * 100D / (double) totalBytes;
        }
    }

    private final long timestampMillis;
    private final double processCpuPercent;
    private final double systemCpuPercent;
    private final int threadCount;
    private final long heapUsedBytes;
    private final long heapMaxBytes;
    private final long nonHeapUsedBytes;
    private final long directUsedBytes;
    private final long directCapacityBytes;
    private final long mappedUsedBytes;
    private final long mappedCapacityBytes;
    private final long metaspaceUsedBytes;
    private final long metaspaceMaxBytes;
    private final List<DiskUsage> disks;
    private final List<DiagnosticMetric> metrics;

    public ResourceSnapshot(long timestampMillis, double processCpuPercent, double systemCpuPercent, int threadCount,
                            long heapUsedBytes, long heapMaxBytes, long nonHeapUsedBytes,
                            long directUsedBytes, long directCapacityBytes, long mappedUsedBytes, long mappedCapacityBytes,
                            long metaspaceUsedBytes, long metaspaceMaxBytes,
                            List<DiskUsage> disks, List<DiagnosticMetric> metrics) {
        this.timestampMillis = timestampMillis;
        this.processCpuPercent = processCpuPercent;
        this.systemCpuPercent = systemCpuPercent;
        this.threadCount = threadCount;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.nonHeapUsedBytes = nonHeapUsedBytes;
        this.directUsedBytes = directUsedBytes;
        this.directCapacityBytes = directCapacityBytes;
        this.mappedUsedBytes = mappedUsedBytes;
        this.mappedCapacityBytes = mappedCapacityBytes;
        this.metaspaceUsedBytes = metaspaceUsedBytes;
        this.metaspaceMaxBytes = metaspaceMaxBytes;
        this.disks = Collections.unmodifiableList(disks);
        this.metrics = Collections.unmodifiableList(metrics);
    }

    public double heapUsedPercent() {
        return percent(heapUsedBytes, heapMaxBytes);
    }

    public double directUsedPercent() {
        return percent(directUsedBytes, directCapacityBytes);
    }

    public double metaspaceUsedPercent() {
        return percent(metaspaceUsedBytes, metaspaceMaxBytes);
    }

    private static double percent(long used, long max) {
        return max <= 0L ? 0D : (double) used * 100D / (double) max;
    }
}
