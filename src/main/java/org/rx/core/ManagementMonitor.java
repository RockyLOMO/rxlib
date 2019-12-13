package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.beans.FlagsEnum;

import java.io.File;
import java.lang.management.*;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.cacheKey;

public class ManagementMonitor implements EventTarget<ManagementMonitor> {
    private static final double PERCENT = 100.0D, k = 1024, m = k * 1024, g = m * 1024, t = g * 1024;
    @Getter
    private static final ManagementMonitor instance = new ManagementMonitor();

    @RequiredArgsConstructor
    @Getter
    public static class DiskMonitorBean {
        private final String name;
        private final long usedSpace, totalSpace;

        public int getUsedPercent() {
            return toPercent((double) usedSpace / totalSpace);
        }

        public String getUsedString() {
            return formatSize(usedSpace);
        }

        public String getTotalString() {
            return formatSize(totalSpace);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class MonitorBean {
        private final int cpuThreads;
        private final double cpuLoad;
        private final int liveThreadCount;
        private final long usedMemory;
        private final long totalMemory;
        private final NQuery<DiskMonitorBean> disks;

        public int getCpuLoadPercent() {
            return toPercent(cpuLoad);
        }

        public String getCpuLoadString() {
            return formatCpu(cpuLoad);
        }

        public int getUsedMemoryPercent() {
            return toPercent((double) usedMemory / totalMemory);
        }

        public String getUsedMemoryString() {
            return formatSize(usedMemory);
        }

        public String getTotalMemoryString() {
            return formatSize(totalMemory);
        }

        public DiskMonitorBean getSummedDisk() {
            return disks.groupBy(p -> true, (p, x) -> new DiskMonitorBean("/", (long) x.sum(y -> y.usedSpace), (long) x.sum(y -> y.totalSpace))).first();
        }
    }

    public static String formatCpu(double val) {
        String p = String.valueOf(val * PERCENT);
        int ix = p.indexOf(".") + 1;
        String percent = p.substring(0, ix) + p.substring(ix, ix + 1);
        return percent + "%";
    }

    public static String formatSize(double val) {
        if (val < k) {
            return String.valueOf(val);
        }
        if (val < m) {
            return String.format("%.1fKB", val / k);
        }
        if (val < g) {
            return String.format("%.1fMB", val / m);
        }
        if (val < t) {
            return String.format("%.1fGB", val / g);
        }
        return String.format("%.1fTB", val / t);
    }

//    /**
//     * 格式化文件大小
//     * 参考：https://stackoverflow.com/a/5599842/1253611
//     *
//     * @param size byte
//     * @return readable file size
//     */
//    public static String formatSize(long size) {
//        if (size <= 0) return "0";
//        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
//        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
//        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
//    }

    private static int toPercent(double val) {
        return (int) Math.ceil(val * PERCENT);
    }

    public volatile BiConsumer<ManagementMonitor, NEventArgs<MonitorBean>> scheduled, cpuWarning, memoryWarning, diskWarning;
    @Setter
    private int cpuWarningThreshold;
    @Setter
    private int memoryWarningThreshold;
    @Setter
    private int diskWarningThreshold;
    private final OperatingSystemMXBean os;
    private final ThreadMXBean thread = ManagementFactory.getThreadMXBean();
//    private MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
//    private List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
//    private Map<NotificationBroadcaster, NotificationListener> gcListeners = new HashMap<>();

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.add(EventFlags.Quietly);
    }

    private ManagementMonitor() {
        os = as(ManagementFactory.getOperatingSystemMXBean(), OperatingSystemMXBean.class);
        if (os == null) {
            throw new InvalidOperationException("getOperatingSystemMXBean fail");
        }
        Tasks.schedule(() -> {
            NEventArgs<MonitorBean> args = new NEventArgs<>(getBean());
            raiseEvent(scheduled, args);
            if (args.getValue().getCpuLoad() >= cpuWarningThreshold) {
                raiseEvent(cpuWarning, args);
            }
            if (args.getValue().getUsedMemoryPercent() >= memoryWarningThreshold) {
                raiseEvent(memoryWarning, args);
            }
            for (DiskMonitorBean disk : args.getValue().getDisks()) {
                if (disk.getUsedPercent() >= diskWarningThreshold) {
                    raiseEvent(diskWarning, args);
                }
            }
        }, 1000);
    }

    public MonitorBean getBean() {
        double cpuLoad = os.getSystemCpuLoad();
        long totalMemory = os.getTotalPhysicalMemorySize();
        return new MonitorBean(os.getAvailableProcessors(), cpuLoad, thread.getThreadCount(),
                totalMemory - os.getFreePhysicalMemorySize(), totalMemory,
                MemoryCache.getOrStore(cacheKey("getBean"), k -> NQuery.of(File.listRoots()).select(p -> new DiskMonitorBean(p.getPath(), p.getTotalSpace() - p.getFreeSpace(), p.getTotalSpace()))));
    }

//    public void run() {
    //        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
//            if (!(gcBean instanceof NotificationBroadcaster)) {
//                continue;
//            }
//            String gcName = gcBean.getName();
//            NotificationListener listener = (notification, handback) -> {
//                if (gcBean instanceof com.sun.management.GarbageCollectorMXBean) {
//                    put(gc, ((com.sun.management.GarbageCollectorMXBean) gcBean).getLastGcInfo().getDuration(), "name", gcName);
//                }
//            };
//            NotificationBroadcaster broadcaster = ((NotificationBroadcaster) gcBean);
//            broadcaster.addNotificationListener(listener, null, null);
//            gcListeners.put(broadcaster, listener);
//        }
//
//        // Runtime rt = Runtime.getRuntime();
//        // add(memory, MB(rt.totalMemory() - rt.freeMemory()), "type", "heap_used");
//        MemoryUsage heap = memory.getHeapMemoryUsage();
//        put(memoryMB, MB(heap.getCommitted()), "type", "heap_committed");
//        long heapUsed = heap.getUsed();
//        put(memoryMB, MB(heapUsed), "type", "heap_used");
//        long heapMax = heap.getMax();
//        put(memoryMB, MB(heapMax), "type", "heap_max");
//        put(memoryPercent, PERCENT(heapUsed, heapMax), "type", "heap");
//        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
//        put(memoryMB, MB(nonHeap.getCommitted()), "type", "non_heap_committed");
//        long nonHeapUsed = nonHeap.getUsed();
//        put(memoryMB, MB(nonHeapUsed), "type", "non_heap_used");
//        long nonHeapMax = nonHeap.getMax();
//        if (nonHeapMax > 0) {
//            put(memoryMB, MB(nonHeapMax), "type", "non_heap_max");
//            put(memoryPercent, PERCENT(nonHeapUsed, nonHeapMax), "type", "non_heap");
//        }
//
//        for (MemoryPoolMXBean memoryPool : memoryPools) {
//            String poolName = memoryPool.getName();
//            MemoryUsage pool = memoryPool.getUsage();
//            if (pool == null) {
//                continue;
//            }
//            put(memoryPoolMB, MB(pool.getCommitted()), "type", "committed", "name", poolName);
//            long poolUsed = pool.getUsed();
//            put(memoryPoolMB, MB(poolUsed), "type", "used", "name", poolName);
//            long poolMax = pool.getMax();
//            if (poolMax > 0) {
//                put(memoryPoolMB, MB(poolMax), "type", "max", "name", poolName);
//                put(memoryPoolPercent, PERCENT(poolUsed, poolMax), "name", poolName);
//            }
//        }
//    }
}
