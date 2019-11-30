package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.beans.FlagsEnum;

import java.lang.management.*;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.as;

public class ManagementMonitor implements EventTarget<ManagementMonitor> {
    @Getter
    private static final ManagementMonitor instance = new ManagementMonitor();

    @RequiredArgsConstructor
    @Getter
    public static class MonitorBean {
        private final int cpuLoadPercent;
        private final int liveThreadCount;
        private final double freePhysicalMemoryMB;
        private final double totalPhysicalMemoryMB;

        public int getMemoryUsedPercent() {
            return (int) Math.ceil((1 - freePhysicalMemoryMB / totalPhysicalMemoryMB) * 100);
        }
    }

    private static double MB(double value) {
        return value / 1048576;
    }

    private static double PERCENT(double dividend, double divisor) {
        return divisor == 0 ? 0 : dividend * 100 / divisor;
    }

    public volatile BiConsumer<ManagementMonitor, NEventArgs<MonitorBean>> scheduled, cpuWarning, memoryWarning;
    @Setter
    private int cpuWarningThreshold;
    @Setter
    private int memoryWarningThreshold;
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
            if (args.getValue().getCpuLoadPercent() >= cpuWarningThreshold) {
                raiseEvent(cpuWarning, args);
            }
            if (args.getValue().getMemoryUsedPercent() >= memoryWarningThreshold) {
                raiseEvent(memoryWarning, args);
            }
        }, 1000);
    }

    private MonitorBean getBean() {
        return new MonitorBean((int) Math.ceil(os.getSystemCpuLoad() * 100), thread.getThreadCount(),
                MB(os.getFreePhysicalMemorySize()), MB(os.getTotalPhysicalMemorySize()));
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
