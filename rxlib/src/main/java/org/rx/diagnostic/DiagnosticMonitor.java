package org.rx.diagnostic;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class DiagnosticMonitor implements AutoCloseable {
    private static volatile DiagnosticMonitor DEFAULT;

    @Getter
    private final DiagnosticConfig config;
    @Getter
    private final DiagnosticStore store;
    private final ResourceSampler sampler;
    private final IncidentBundleWriter bundleWriter;
    private final ResourceSnapshot[] ring;
    private final AtomicLong sampleSeq = new AtomicLong();
    private final AtomicLong incidentSeq = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<DiagnosticIncidentType, TriggerState> triggerStates = new EnumMap<>(DiagnosticIncidentType.class);
    private final Object fileIoWindowLock = new Object();
    private final AtomicLong lastJfrMillis = new AtomicLong();
    private final AtomicLong lastClassHistogramMillis = new AtomicLong();
    private final AtomicLong lastHeapDumpMillis = new AtomicLong();
    private final AtomicLong lastBundleCleanupMillis = new AtomicLong();

    private ScheduledExecutorService scheduler;
    private ExecutorService evidenceExecutor;
    private volatile long diagUntilMillis;
    private long fileIoWindowStartMillis;
    private long fileIoWindowBytes;

    public DiagnosticMonitor(DiagnosticConfig config) {
        this(config, null);
    }

    public DiagnosticMonitor(DiagnosticConfig config, DiagnosticStore store) {
        this.config = config == null ? new DiagnosticConfig() : config;
        this.config.normalize();
        this.store = store == null ? (this.config.isH2Enabled() ? new H2DiagnosticStore(this.config) : NoopDiagnosticStore.INSTANCE) : store;
        this.sampler = new ResourceSampler();
        this.bundleWriter = new IncidentBundleWriter(this.config);
        this.ring = new ResourceSnapshot[this.config.getRingBufferMaxSamples()];
        for (DiagnosticIncidentType type : DiagnosticIncidentType.values()) {
            triggerStates.put(type, new TriggerState());
        }
    }

    public static DiagnosticMonitor startDefault() {
        DiagnosticMonitor monitor = new DiagnosticMonitor(RxConfig.INSTANCE.getDiagnostic());
        monitor.start();
        return monitor;
    }

    public static DiagnosticMonitor getDefault() {
        return DEFAULT;
    }

    public void start() {
        if (!config.isEnabled() || config.getLevel() == DiagnosticLevel.OFF) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        store.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("rx-diagnostic-sampler"));
        evidenceExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("rx-diagnostic-evidence"));
        DEFAULT = this;
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    sampleOnce();
                } catch (Throwable e) {
                    log.warn("diagnostic sample failed", e);
                }
            }
        }, 0L, config.getSampleIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public DiagnosticLevel currentLevel() {
        if (!running.get()) {
            return DiagnosticLevel.OFF;
        }
        if (System.currentTimeMillis() < diagUntilMillis) {
            return DiagnosticLevel.DIAG;
        }
        return config.getLevel();
    }

    public ResourceSnapshot sampleOnce() {
        ResourceSnapshot snapshot = sampler.sample();
        long seq = sampleSeq.getAndIncrement();
        ring[(int) (seq % ring.length)] = snapshot;
        for (DiagnosticMetric metric : snapshot.getMetrics()) {
            store.recordMetric(metric);
        }
        evaluate(snapshot);
        cleanupBundles(snapshot.getTimestampMillis());
        return snapshot;
    }

    public List<ResourceSnapshot> recentSnapshots() {
        List<ResourceSnapshot> list = new ArrayList<>(ring.length);
        for (ResourceSnapshot snapshot : ring) {
            if (snapshot != null) {
                list.add(snapshot);
            }
        }
        Collections.sort(list, new Comparator<ResourceSnapshot>() {
            @Override
            public int compare(ResourceSnapshot o1, ResourceSnapshot o2) {
                return Long.compare(o1.getTimestampMillis(), o2.getTimestampMillis());
            }
        });
        return list;
    }

    void recordFileIo(String path, DiagnosticFileOperation operation, long bytes, long elapsedNanos) {
        if (!running.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        updateFileIoWindow(now, Math.max(0L, bytes));
        double rate = config.effectiveFileIoSampleRate(currentLevel());
        if (rate <= 0D || (rate < 1D && ThreadLocalRandom.current().nextDouble() > rate)) {
            return;
        }
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        long stackHash = StackTraceCodec.hash(stackTrace, config.getMaxStackFrames());
        String stackText = StackTraceCodec.format(stackTrace, config.getMaxStackFrames());
        store.recordStackTrace(stackHash, stackText, now);
        store.recordFileIo(now, path, operation, bytes, elapsedNanos, stackHash, null);
    }

    @Override
    public void close() {
        running.set(false);
        if (DEFAULT == this) {
            DEFAULT = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (evidenceExecutor != null) {
            evidenceExecutor.shutdownNow();
        }
        store.close();
    }

    private void evaluate(ResourceSnapshot snapshot) {
        long now = snapshot.getTimestampMillis();
        checkCpu(snapshot, now);
        checkMemory(snapshot, now);
        checkDisk(snapshot, now);
    }

    private void cleanupBundles(long now) {
        long last = lastBundleCleanupMillis.get();
        if (now - last < 60000L || !lastBundleCleanupMillis.compareAndSet(last, now)) {
            return;
        }
        bundleWriter.cleanup(now);
    }

    private void checkCpu(ResourceSnapshot snapshot, long now) {
        boolean high = snapshot.getProcessCpuPercent() >= config.getCpuThresholdPercent();
        updateTrigger(DiagnosticIncidentType.CPU_HIGH, high, now, config.getCpuSustainMillis(),
                "processCpu=" + snapshot.getProcessCpuPercent());
    }

    private void checkMemory(ResourceSnapshot snapshot, long now) {
        updateTrigger(DiagnosticIncidentType.HEAP_MEMORY_HIGH,
                snapshot.heapUsedPercent() >= config.getHeapUsedThresholdPercent(),
                now, config.getSampleIntervalMillis(),
                "heapUsedPercent=" + snapshot.heapUsedPercent());

        updateTrigger(DiagnosticIncidentType.DIRECT_MEMORY_HIGH,
                snapshot.directUsedPercent() >= config.getDirectUsedThresholdPercent() && snapshot.getDirectCapacityBytes() > 0L,
                now, config.getSampleIntervalMillis(),
                "directUsedBytes=" + snapshot.getDirectUsedBytes());

        updateTrigger(DiagnosticIncidentType.METASPACE_HIGH,
                snapshot.metaspaceUsedPercent() >= config.getMetaspaceUsedThresholdPercent() && snapshot.getMetaspaceMaxBytes() > 0L,
                now, config.getSampleIntervalMillis(),
                "metaspaceUsedPercent=" + snapshot.metaspaceUsedPercent());
    }

    private void checkDisk(ResourceSnapshot snapshot, long now) {
        for (ResourceSnapshot.DiskUsage disk : snapshot.getDisks()) {
            boolean lowPercent = disk.getFreePercent() <= config.getDiskFreePercentThreshold();
            boolean lowBytes = disk.getFreeBytes() <= config.getDiskMinFreeBytes();
            if (lowPercent || lowBytes) {
                updateTrigger(DiagnosticIncidentType.DISK_SPACE_HIGH, true, now, config.getSampleIntervalMillis(),
                        "path=" + disk.getPath() + ",freePercent=" + disk.getFreePercent() + ",freeBytes=" + disk.getFreeBytes());
                return;
            }
        }
        updateTrigger(DiagnosticIncidentType.DISK_SPACE_HIGH, false, now, config.getSampleIntervalMillis(), null);
    }

    private void updateFileIoWindow(long now, long bytes) {
        long threshold = config.getDiskIoBytesPerSecondThreshold();
        if (threshold <= 0L) {
            return;
        }
        synchronized (fileIoWindowLock) {
            if (fileIoWindowStartMillis == 0L) {
                fileIoWindowStartMillis = now;
            }
            fileIoWindowBytes += bytes;
            long elapsed = now - fileIoWindowStartMillis;
            if (elapsed < 1000L) {
                return;
            }
            long bytesPerSecond = elapsed <= 0L ? 0L : fileIoWindowBytes * 1000L / elapsed;
            fileIoWindowStartMillis = now;
            fileIoWindowBytes = 0L;
            updateTrigger(DiagnosticIncidentType.DISK_IO_HIGH, bytesPerSecond >= threshold, now,
                    config.getDiskIoSustainMillis(), "fileIoBytesPerSecond=" + bytesPerSecond);
        }
    }

    private void updateTrigger(DiagnosticIncidentType type, boolean high, long now, long sustainMillis, String summary) {
        TriggerState state = triggerStates.get(type);
        if (state == null) {
            return;
        }
        if (!high) {
            state.highSinceMillis = 0L;
            return;
        }
        if (state.highSinceMillis == 0L) {
            state.highSinceMillis = now;
        }
        if (now - state.highSinceMillis < Math.max(0L, sustainMillis)) {
            return;
        }
        if (now - state.lastIncidentMillis < config.getIncidentCooldownMillis()) {
            return;
        }
        state.lastIncidentMillis = now;
        openIncident(type, DiagnosticLevel.DIAG, now, summary == null ? type.name() : summary);
    }

    private void openIncident(final DiagnosticIncidentType type, final DiagnosticLevel level, final long now, final String summary) {
        final String incidentId = now + "-" + type.name().toLowerCase() + "-" + incidentSeq.incrementAndGet();
        diagUntilMillis = Math.max(diagUntilMillis, now + config.getDiagDurationMillis());
        final File bundleDir = bundleWriter.createBundleDir(incidentId, type);
        store.recordIncident(incidentId, type, level, now, 0L, summary, bundleDir == null ? null : bundleDir.getAbsolutePath());
        if (evidenceExecutor == null) {
            return;
        }
        evidenceExecutor.execute(new Runnable() {
            @Override
            public void run() {
                collectEvidence(incidentId, type, level, now, summary, bundleDir);
            }
        });
    }

    private void collectEvidence(String incidentId, DiagnosticIncidentType type, DiagnosticLevel level,
                                 long startMillis, String summary, File bundleDir) {
        StringBuilder incidentSummary = new StringBuilder(512);
        incidentSummary.append("incidentId=").append(incidentId).append('\n');
        incidentSummary.append("type=").append(type).append('\n');
        incidentSummary.append("level=").append(level).append('\n');
        incidentSummary.append("summary=").append(summary).append('\n');
        incidentSummary.append("jfrAvailable=").append(JvmDiagnosticSupport.isJfrAvailable()).append('\n');

        if (bundleDir == null) {
            incidentSummary.append("bundle=skipped-no-disk-budget").append('\n');
        }

        if (type != DiagnosticIncidentType.DISK_SPACE_HIGH) {
            tryStartJfr(incidentId, bundleDir, incidentSummary);
        }

        if (type == DiagnosticIncidentType.CPU_HIGH) {
            collectCpuEvidence(incidentId, incidentSummary, bundleDir);
        } else if (type == DiagnosticIncidentType.DISK_SPACE_HIGH) {
            collectDiskEvidence(incidentId, incidentSummary);
        } else if (type == DiagnosticIncidentType.DISK_IO_HIGH) {
            incidentSummary.append("diskIoEvidence=file-io-samples").append('\n');
        } else {
            collectJvmEvidence(incidentId, type, level, incidentSummary, bundleDir);
        }

        File summaryFile = bundleWriter.writeText(bundleDir, "summary.txt", incidentSummary.toString());
        long endMillis = System.currentTimeMillis();
        store.recordIncident(incidentId, type, level, startMillis, endMillis, incidentSummary.toString(),
                bundleDir == null ? null : bundleDir.getAbsolutePath());
    }

    private void collectCpuEvidence(String incidentId, StringBuilder incidentSummary, File bundleDir) {
        StringBuilder stacks = new StringBuilder(4096);
        for (int i = 0; i < config.getCpuEvidenceSamples(); i++) {
            try {
                List<ThreadCpuSample> samples = sampler.sampleTopThreads(config.getCpuEvidenceIntervalMillis(),
                        config.getCpuTopThreads(), config.getMaxStackFrames());
                for (ThreadCpuSample sample : samples) {
                    store.recordStackTrace(sample.getStackHash(), sample.getStackTrace(), sample.getTimestampMillis());
                    store.recordThreadCpu(sample, incidentId);
                    stacks.append("cpuDeltaNanos=").append(sample.getCpuDeltaNanos())
                            .append(" thread=").append(sample.getThreadName())
                            .append(" id=").append(sample.getThreadId()).append('\n')
                            .append(sample.getStackTrace()).append('\n');
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable e) {
                incidentSummary.append("cpuEvidenceError=").append(e.toString()).append('\n');
            }
        }
        bundleWriter.writeText(bundleDir, "cpu-stacks.txt", stacks.toString());
        String threadDump = JvmDiagnosticSupport.threadPrint();
        bundleWriter.writeText(bundleDir, "thread-dump.txt", threadDump);
    }

    private void collectJvmEvidence(String incidentId, DiagnosticIncidentType type, DiagnosticLevel level,
                                    StringBuilder incidentSummary, File bundleDir) {
        if (DiagnosticFileSupport.hasUsableSpace(bundleDir, config.getEvidenceMinFreeBytes()) && allowHeavyEvidence(lastClassHistogramMillis)) {
            String histogram = JvmDiagnosticSupport.classHistogram(false);
            if (histogram != null) {
                bundleWriter.writeText(bundleDir, "class-histogram.txt", histogram);
            } else {
                incidentSummary.append("classHistogram=unavailable").append('\n');
            }
        } else {
            incidentSummary.append("classHistogram=skipped-budget-or-cooldown").append('\n');
        }
        if (config.isNativeMemoryTrackingEnabled()) {
            String nmt = JvmDiagnosticSupport.nativeMemorySummary();
            if (nmt != null) {
                bundleWriter.writeText(bundleDir, "nmt-summary.txt", nmt);
            }
        }
        if (config.isHeapDumpEnabled() && level.atLeast(DiagnosticLevel.FORENSIC)
                && type != DiagnosticIncidentType.DISK_SPACE_HIGH) {
            if (bundleDir != null && DiagnosticFileSupport.hasUsableSpace(bundleDir, config.getHeapDumpMinFreeBytes())
                    && allowHeavyEvidence(lastHeapDumpMillis)) {
                File heapFile = new File(bundleDir, incidentId + ".hprof");
                boolean dumped = JvmDiagnosticSupport.dumpHeap(heapFile, true);
                incidentSummary.append("heapDump=").append(dumped ? heapFile.getAbsolutePath() : "failed").append('\n');
            } else {
                incidentSummary.append("heapDump=skipped-budget-or-cooldown").append('\n');
            }
        }
    }

    private void collectDiskEvidence(String incidentId, StringBuilder incidentSummary) {
        if (!config.isDiskScanEnabled()) {
            incidentSummary.append("diskScan=disabled").append('\n');
            return;
        }
        List<File> roots = new ArrayList<>(config.getDiskScanRoots());
        if (roots.isEmpty()) {
            File[] fileRoots = File.listRoots();
            if (fileRoots != null) {
                Collections.addAll(roots, fileRoots);
            }
        }
        for (File root : roots) {
            scanRoot(root, incidentId, incidentSummary);
        }
    }

    private void scanRoot(File root, String incidentId, StringBuilder incidentSummary) {
        if (root == null || !root.exists()) {
            return;
        }
        long deadline = System.currentTimeMillis() + config.getDiskScanTimeoutMillis();
        int scanned = 0;
        List<FileSizeItem> top = new ArrayList<>(config.getDiskScanTopFiles() + 1);
        ArrayDeque<FileDepth> deque = new ArrayDeque<>();
        deque.add(new FileDepth(root, 0));
        while (!deque.isEmpty() && scanned < config.getDiskScanMaxFiles() && System.currentTimeMillis() < deadline) {
            FileDepth current = deque.pollFirst();
            File file = current.file;
            scanned++;
            if ((scanned & 511) == 0) {
                Thread.yield();
            }
            if (file.isFile()) {
                addTopFile(top, new FileSizeItem(file, safeLength(file), file.lastModified()));
                continue;
            }
            if (current.depth >= config.getDiskScanMaxDepth()) {
                continue;
            }
            File[] children = file.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                deque.addLast(new FileDepth(child, current.depth + 1));
            }
        }
        long now = System.currentTimeMillis();
        Collections.sort(top, new Comparator<FileSizeItem>() {
            @Override
            public int compare(FileSizeItem o1, FileSizeItem o2) {
                return Long.compare(o2.size, o1.size);
            }
        });
        for (FileSizeItem item : top) {
            store.recordFileSize(now, item.file.getAbsolutePath(), item.size, item.lastModified, incidentId);
        }
        incidentSummary.append("diskScanRoot=").append(root.getAbsolutePath())
                .append(",scanned=").append(scanned)
                .append(",topFiles=").append(top.size()).append('\n');
    }

    private void addTopFile(List<FileSizeItem> top, FileSizeItem item) {
        top.add(item);
        Collections.sort(top, new Comparator<FileSizeItem>() {
            @Override
            public int compare(FileSizeItem o1, FileSizeItem o2) {
                return Long.compare(o2.size, o1.size);
            }
        });
        if (top.size() > config.getDiskScanTopFiles()) {
            top.remove(top.size() - 1);
        }
    }

    private long safeLength(File file) {
        try {
            return file.length();
        } catch (Throwable e) {
            return 0L;
        }
    }

    private void tryStartJfr(String incidentId, File bundleDir, StringBuilder incidentSummary) {
        if (!JvmDiagnosticSupport.shouldTryJfr(config.getJfrMode())) {
            return;
        }
        if (bundleDir == null || !DiagnosticFileSupport.hasUsableSpace(bundleDir, config.getJfrMinFreeBytes())
                || !allowHeavyEvidence(lastJfrMillis)) {
            incidentSummary.append("jfr=skipped-budget-or-cooldown").append('\n');
            return;
        }
        try {
            File jfrFile = new File(bundleDir, incidentId + ".jfr");
            String result = JvmDiagnosticSupport.startJfr("rxdiag-" + incidentId, jfrFile,
                    config.getJfrSettings(), config.getJfrDurationSeconds());
            if (result != null) {
                bundleWriter.writeText(bundleDir, "jfr-start.txt", result);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean allowHeavyEvidence(AtomicLong holder) {
        long cooldown = config.getHeavyEvidenceCooldownMillis();
        if (cooldown <= 0L) {
            holder.set(System.currentTimeMillis());
            return true;
        }
        long now = System.currentTimeMillis();
        long last = holder.get();
        if (now - last < cooldown) {
            return false;
        }
        return holder.compareAndSet(last, now);
    }

    private static final class TriggerState {
        long highSinceMillis;
        long lastIncidentMillis;
    }

    private static final class FileDepth {
        final File file;
        final int depth;

        FileDepth(File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private static final class FileSizeItem {
        final File file;
        final long size;
        final long lastModified;

        FileSizeItem(File file, long size, long lastModified) {
            this.file = file;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong seq = new AtomicLong();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    }
}
