package org.rx.diagnostic;

import java.io.File;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ResourceSampler {
    private final java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    public ResourceSampler() {
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (Throwable ignored) {
                // Some runtimes disallow changing this flag.
            }
        }
    }

    public ResourceSnapshot sample() {
        long now = System.currentTimeMillis();
        List<DiagnosticMetric> metrics = new ArrayList<>(64);

        double processCpu = percentCpu(readDouble(osBean, "getProcessCpuLoad"));
        double systemCpu = percentCpu(readDouble(osBean, "getSystemCpuLoad"));
        int threadCount = threadBean.getThreadCount();
        add(metrics, now, "process.cpu.percent", processCpu, null);
        add(metrics, now, "system.cpu.percent", systemCpu, null);
        add(metrics, now, "jvm.thread.count", threadCount, null);
        add(metrics, now, "jvm.thread.daemon.count", threadBean.getDaemonThreadCount(), null);
        add(metrics, now, "jvm.thread.peak.count", threadBean.getPeakThreadCount(), null);

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long heapUsed = heap.getUsed();
        long heapMax = heap.getMax();
        long nonHeapUsed = nonHeap.getUsed();
        add(metrics, now, "jvm.heap.used.bytes", heapUsed, null);
        add(metrics, now, "jvm.heap.committed.bytes", heap.getCommitted(), null);
        add(metrics, now, "jvm.heap.max.bytes", heapMax, null);
        add(metrics, now, "jvm.nonheap.used.bytes", nonHeapUsed, null);
        add(metrics, now, "jvm.nonheap.committed.bytes", nonHeap.getCommitted(), null);

        long metaspaceUsed = 0L;
        long metaspaceMax = -1L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = safeUsage(pool);
            if (usage == null) {
                continue;
            }
            String tags = "pool=" + pool.getName();
            add(metrics, now, "jvm.memory.pool.used.bytes", usage.getUsed(), tags);
            add(metrics, now, "jvm.memory.pool.committed.bytes", usage.getCommitted(), tags);
            add(metrics, now, "jvm.memory.pool.max.bytes", usage.getMax(), tags);
            if (pool.getName() != null && pool.getName().toLowerCase().contains("metaspace")) {
                metaspaceUsed += Math.max(0L, usage.getUsed());
                metaspaceMax = usage.getMax();
            }
        }

        long directUsed = 0L;
        long directCapacity = 0L;
        long mappedUsed = 0L;
        long mappedCapacity = 0L;
        for (BufferPoolMXBean buffer : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = buffer.getName();
            long used = buffer.getMemoryUsed();
            long capacity = buffer.getTotalCapacity();
            String tags = "pool=" + name;
            add(metrics, now, "jvm.buffer.count", buffer.getCount(), tags);
            add(metrics, now, "jvm.buffer.used.bytes", used, tags);
            add(metrics, now, "jvm.buffer.capacity.bytes", capacity, tags);
            if ("direct".equals(name)) {
                directUsed = used;
                directCapacity = capacity;
            } else if ("mapped".equals(name)) {
                mappedUsed = used;
                mappedCapacity = capacity;
            }
        }

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String tags = "gc=" + gc.getName();
            add(metrics, now, "jvm.gc.count", gc.getCollectionCount(), tags);
            add(metrics, now, "jvm.gc.time.millis", gc.getCollectionTime(), tags);
        }

        addPhysicalMemory(metrics, now);

        List<ResourceSnapshot.DiskUsage> disks = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                String path = root.getAbsolutePath();
                disks.add(new ResourceSnapshot.DiskUsage(path, total, free));
                String tags = "path=" + path;
                add(metrics, now, "disk.total.bytes", total, tags);
                add(metrics, now, "disk.free.bytes", free, tags);
                add(metrics, now, "disk.used.bytes", Math.max(0L, total - free), tags);
                add(metrics, now, "disk.free.percent", total <= 0L ? 100D : (double) free * 100D / (double) total, tags);
            }
        }

        return new ResourceSnapshot(now, processCpu, systemCpu, threadCount, heapUsed, heapMax, nonHeapUsed,
                directUsed, directCapacity, mappedUsed, mappedCapacity, metaspaceUsed, metaspaceMax, disks, metrics);
    }

    public List<ThreadCpuSample> sampleTopThreads(long intervalMillis, int topN, int maxStackFrames) throws InterruptedException {
        if (!threadBean.isThreadCpuTimeSupported()) {
            return Collections.emptyList();
        }
        if (!threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (Throwable e) {
                return Collections.emptyList();
            }
        }
        long[] ids = threadBean.getAllThreadIds();
        long[] begin = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            begin[i] = safeThreadCpu(ids[i]);
        }
        Thread.sleep(Math.max(1L, intervalMillis));
        long now = System.currentTimeMillis();
        List<ThreadDelta> deltas = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            long end = safeThreadCpu(ids[i]);
            long start = begin[i];
            if (start >= 0L && end >= start) {
                long delta = end - start;
                if (delta > 0L) {
                    deltas.add(new ThreadDelta(ids[i], delta));
                }
            }
        }
        if (deltas.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(deltas, new Comparator<ThreadDelta>() {
            @Override
            public int compare(ThreadDelta o1, ThreadDelta o2) {
                return Long.compare(o2.cpuDeltaNanos, o1.cpuDeltaNanos);
            }
        });
        int limit = Math.min(topN, deltas.size());
        long[] topIds = new long[limit];
        for (int i = 0; i < limit; i++) {
            topIds[i] = deltas.get(i).threadId;
        }
        ThreadInfo[] infos = threadBean.getThreadInfo(topIds, maxStackFrames);
        List<ThreadCpuSample> samples = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ThreadInfo info = infos[i];
            if (info == null) {
                continue;
            }
            StackTraceElement[] stackTrace = info.getStackTrace();
            long hash = StackTraceCodec.hash(stackTrace, maxStackFrames);
            String text = StackTraceCodec.format(info, maxStackFrames);
            samples.add(new ThreadCpuSample(now, info.getThreadId(), info.getThreadName(),
                    info.getThreadState().name(), deltas.get(i).cpuDeltaNanos, hash, text));
        }
        return samples;
    }

    private void addPhysicalMemory(List<DiagnosticMetric> metrics, long now) {
        long freePhysical = -1L;
        long totalPhysical = -1L;
        long openFd = -1L;
        long maxFd = -1L;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean mx = (com.sun.management.OperatingSystemMXBean) osBean;
            freePhysical = mx.getFreePhysicalMemorySize();
            totalPhysical = mx.getTotalPhysicalMemorySize();
        }
        if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean) {
            com.sun.management.UnixOperatingSystemMXBean mx = (com.sun.management.UnixOperatingSystemMXBean) osBean;
            openFd = mx.getOpenFileDescriptorCount();
            maxFd = mx.getMaxFileDescriptorCount();
        }
        if (freePhysical < 0L) {
            freePhysical = readLong(osBean, "getFreePhysicalMemorySize");
        }
        if (totalPhysical < 0L) {
            totalPhysical = readLong(osBean, "getTotalPhysicalMemorySize");
        }
        if (openFd < 0L) {
            openFd = readLong(osBean, "getOpenFileDescriptorCount");
        }
        if (maxFd < 0L) {
            maxFd = readLong(osBean, "getMaxFileDescriptorCount");
        }
        if (freePhysical >= 0L) {
            add(metrics, now, "system.physical.free.bytes", freePhysical, null);
        }
        if (totalPhysical >= 0L) {
            add(metrics, now, "system.physical.total.bytes", totalPhysical, null);
        }
        if (openFd >= 0L) {
            add(metrics, now, "process.fd.open.count", openFd, null);
        }
        if (maxFd >= 0L) {
            add(metrics, now, "process.fd.max.count", maxFd, null);
        }
    }

    private long safeThreadCpu(long threadId) {
        try {
            return threadBean.getThreadCpuTime(threadId);
        } catch (Throwable e) {
            return -1L;
        }
    }

    private static MemoryUsage safeUsage(MemoryPoolMXBean pool) {
        try {
            return pool.getUsage();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void add(List<DiagnosticMetric> metrics, long now, String name, double value, String tags) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        metrics.add(new DiagnosticMetric(now, name, value, tags, null));
    }

    private static double percentCpu(double value) {
        if (value < 0D || Double.isNaN(value)) {
            return -1D;
        }
        return value <= 1D ? value * 100D : value;
    }

    private static double readDouble(Object target, String methodName) {
        if (target instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean mx = (com.sun.management.OperatingSystemMXBean) target;
            if ("getProcessCpuLoad".equals(methodName)) {
                return mx.getProcessCpuLoad();
            }
            if ("getSystemCpuLoad".equals(methodName)) {
                return mx.getSystemCpuLoad();
            }
        }
        Object value = invokeNoArg(target, methodName);
        return value instanceof Number ? ((Number) value).doubleValue() : -1D;
    }

    private static long readLong(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static final class ThreadDelta {
        final long threadId;
        final long cpuDeltaNanos;

        ThreadDelta(long threadId, long cpuDeltaNanos) {
            this.threadId = threadId;
            this.cpuDeltaNanos = cpuDeltaNanos;
        }
    }
}
