package org.rx.core;

import com.sun.management.OperatingSystemMXBean;

import javax.management.ListenerNotFoundException;
import javax.management.NotificationBroadcaster;
import javax.management.NotificationListener;
import java.lang.management.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ManagementMonitor implements Runnable, AutoCloseable {
    private static double MB(long value) {
        return (double) value / 1048576;
    }

    private static double PERCENT(long dividend, long divisor) {
        return divisor == 0 ? 0 : (double) dividend * 100 / divisor;
    }

    private static Map<String, String> getTagMap(String... tagPairs) {
        Map<String, String> tagMap = new HashMap<>();
//        MetricKey.putTagMap(tagMap, tagPairs);
        return tagMap;
    }

    private String cpu, threads, memoryMB, memoryPercent;
    private String memoryPoolMB, memoryPoolPercent;
    private Map<String, String> tagMap;
    private ThreadMXBean thread = ManagementFactory.getThreadMXBean();
    private MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
    private OperatingSystemMXBean os = null;
    private Map<NotificationBroadcaster, NotificationListener> gcListeners = new HashMap<>();

    private void put(String name, double value, String... tagPairs) {
        Map<String, String> tagMap_ = new HashMap<>(tagMap);
//        MetricKey.putTagMap(tagMap_, tagPairs);
//        Metric.put(name, value, tagMap_);
    }

    public ManagementMonitor(String prefix, String... tagPairs) {
        this(prefix, getTagMap(tagPairs));
    }

    public ManagementMonitor(String prefix, Map<String, String> tagMap) {
        cpu = prefix + ".cpu";
        threads = prefix + ".threads";
        memoryMB = prefix + ".memory.mb";
        memoryPercent = prefix + ".memory.percent";
        memoryPoolMB = prefix + ".memory_pool.mb";
        memoryPoolPercent = prefix + ".memory_pool.percent";
        java.lang.management.OperatingSystemMXBean os_ = ManagementFactory.getOperatingSystemMXBean();
        if (os_ instanceof OperatingSystemMXBean) {
            os = (OperatingSystemMXBean) os_;
        }
        this.tagMap = tagMap;

        String gc = prefix + ".gc.time";
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gcBean instanceof NotificationBroadcaster)) {
                continue;
            }
            String gcName = gcBean.getName();
            NotificationListener listener = (notification, handback) -> {
                if (gcBean instanceof com.sun.management.GarbageCollectorMXBean) {
                    put(gc, ((com.sun.management.GarbageCollectorMXBean) gcBean).getLastGcInfo().getDuration(), "name", gcName);
                }
            };
            NotificationBroadcaster broadcaster = ((NotificationBroadcaster) gcBean);
            broadcaster.addNotificationListener(listener, null, null);
            gcListeners.put(broadcaster, listener);
        }
    }

    @Override
    public void run() {
        put(threads, thread.getThreadCount(), "type", "total");
        put(threads, thread.getDaemonThreadCount(), "type", "daemon");

        // Runtime rt = Runtime.getRuntime();
        // add(memory, MB(rt.totalMemory() - rt.freeMemory()), "type", "heap_used");
        MemoryUsage heap = memory.getHeapMemoryUsage();
        put(memoryMB, MB(heap.getCommitted()), "type", "heap_committed");
        long heapUsed = heap.getUsed();
        put(memoryMB, MB(heapUsed), "type", "heap_used");
        long heapMax = heap.getMax();
        put(memoryMB, MB(heapMax), "type", "heap_max");
        put(memoryPercent, PERCENT(heapUsed, heapMax), "type", "heap");
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        put(memoryMB, MB(nonHeap.getCommitted()), "type", "non_heap_committed");
        long nonHeapUsed = nonHeap.getUsed();
        put(memoryMB, MB(nonHeapUsed), "type", "non_heap_used");
        long nonHeapMax = nonHeap.getMax();
        if (nonHeapMax > 0) {
            put(memoryMB, MB(nonHeapMax), "type", "non_heap_max");
            put(memoryPercent, PERCENT(nonHeapUsed, nonHeapMax), "type", "non_heap");
        }

        for (MemoryPoolMXBean memoryPool : memoryPools) {
            String poolName = memoryPool.getName();
            MemoryUsage pool = memoryPool.getUsage();
            if (pool == null) {
                continue;
            }
            put(memoryPoolMB, MB(pool.getCommitted()), "type", "committed", "name", poolName);
            long poolUsed = pool.getUsed();
            put(memoryPoolMB, MB(poolUsed), "type", "used", "name", poolName);
            long poolMax = pool.getMax();
            if (poolMax > 0) {
                put(memoryPoolMB, MB(poolMax), "type", "max", "name", poolName);
                put(memoryPoolPercent, PERCENT(poolUsed, poolMax), "name", poolName);
            }
        }

        if (os == null) {
            return;
        }
        long totalPhysical = os.getTotalPhysicalMemorySize();
        put(memoryMB, MB(totalPhysical), "type", "physical_total");
        long usedPhysical = totalPhysical - os.getFreePhysicalMemorySize();
        put(memoryMB, MB(usedPhysical), "type", "physical_used");
        put(memoryPercent, PERCENT(usedPhysical, totalPhysical), "type", "physical");
        long totalSwap = os.getTotalSwapSpaceSize();
        put(memoryMB, MB(totalSwap), "type", "swap_total");
        long usedSwap = totalSwap - os.getFreeSwapSpaceSize();
        put(memoryMB, MB(usedSwap), "type", "swap_used");
        put(memoryPercent, PERCENT(usedSwap, totalSwap), "type", "swap");
        put(memoryMB, MB(os.getCommittedVirtualMemorySize()), "type", "process_committed");

        put(cpu, Math.max(os.getSystemCpuLoad() * 100, 0), "type", "system");
        put(cpu, Math.max(os.getProcessCpuLoad() * 100, 0), "type", "process");
    }

    @Override
    public void close() {
        gcListeners.forEach((broadcaster, listener) -> {
            try {
                broadcaster.removeNotificationListener(listener);
            } catch (ListenerNotFoundException e) {
                // Ignored
            }
        });
        gcListeners.clear();
    }
}
