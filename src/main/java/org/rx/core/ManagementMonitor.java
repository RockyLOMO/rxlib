package org.rx.core;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.bean.FlagsEnum;
import org.rx.io.Bytes;

import java.io.File;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;

import static org.rx.core.App.cacheKey;
import static org.rx.core.App.hashKey;
import static org.rx.core.Constants.PERCENT;

//JMX监控JVM，K8S监控网络，这里简单处理Thread相关
public class ManagementMonitor implements EventTarget<ManagementMonitor> {
    @Getter
    private static final ManagementMonitor instance = new ManagementMonitor();

    @RequiredArgsConstructor
    @Getter
    public static class DiskMonitorInfo implements Serializable {
        private static final long serialVersionUID = 743624611466728938L;
        private final String name;
        private final long usedSpace, totalSpace;

        public int getUsedPercent() {
            return toPercent((double) usedSpace / totalSpace);
        }

        public String getUsedString() {
            return Bytes.readableByteSize(usedSpace);
        }

        public String getTotalString() {
            return Bytes.readableByteSize(totalSpace);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class ThreadMonitorInfo {
        private final ThreadInfo threadInfo;
        private final long cpuTime;

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder(threadInfo.toString());
            int i = s.indexOf("\n");
            s.insert(i, String.format(" cpuTime=%s", cpuTime));
            return s.toString();
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class MonitorInfo implements Serializable {
        private static final long serialVersionUID = -5980065718359999352L;
        private final int cpuThreads;
        private final double cpuLoad;
        private final int liveThreadCount;
        private final long usedMemory;
        private final long totalMemory;
        private final NQuery<DiskMonitorInfo> disks;

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
            return Bytes.readableByteSize(usedMemory);
        }

        public String getTotalMemoryString() {
            return Bytes.readableByteSize(totalMemory);
        }

        public DiskMonitorInfo getSummedDisk() {
            return disks.groupBy(p -> true, (p, x) -> new DiskMonitorInfo("/", (long) x.sum(y -> y.usedSpace), (long) x.sum(y -> y.totalSpace))).first();
        }
    }

    public static String formatCpu(double val) {
        String p = String.valueOf(val * PERCENT);
        int ix = p.indexOf(".") + 1;
        String percent = p.substring(0, ix) + p.charAt(ix);
        return percent + "%";
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

    public final Delegate<ManagementMonitor, NEventArgs<MonitorInfo>> onScheduled = Delegate.create(),
            onCpuWarning = Delegate.create(),
            onMemoryWarning = Delegate.create(),
            onDiskWarning = Delegate.create();
    @Setter
    private int cpuWarningThreshold;
    @Setter
    private int memoryWarningThreshold;
    @Setter
    private int diskWarningThreshold;
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final HotSpotDiagnosticMXBean diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags(EventFlags.QUIETLY);
    }

    private ManagementMonitor() {
        Tasks.schedule(() -> {
            NEventArgs<MonitorInfo> args = new NEventArgs<>(getInfo());
            raiseEvent(onScheduled, args);
            if (args.getValue().getCpuLoad() >= cpuWarningThreshold) {
                raiseEvent(onCpuWarning, args);
            }
            if (args.getValue().getUsedMemoryPercent() >= memoryWarningThreshold) {
                raiseEvent(onMemoryWarning, args);
            }
            for (DiskMonitorInfo disk : args.getValue().getDisks()) {
                if (disk.getUsedPercent() >= diskWarningThreshold) {
                    raiseEvent(onDiskWarning, args);
                }
            }
        }, 5000);
    }

    public MonitorInfo getInfo() {
        long totalMemory = os.getTotalPhysicalMemorySize();
        return new MonitorInfo(os.getAvailableProcessors(), os.getSystemCpuLoad(), threads.getThreadCount(),
                totalMemory - os.getFreePhysicalMemorySize(), totalMemory,
                Cache.getOrSet(hashKey("monitorInfo"), k -> NQuery.of(File.listRoots()).select(p -> new DiskMonitorInfo(p.getPath(), p.getTotalSpace() - p.getFreeSpace(), p.getTotalSpace())), Cache.MEMORY_CACHE));
    }

    public ThreadInfo[] findDeadlockedThreads() {
        long[] deadlockedThreads = Arrays.addAll(threads.findDeadlockedThreads(), threads.findMonitorDeadlockedThreads());
        return threads.getThreadInfo(deadlockedThreads, false, false);
    }

    public List<ThreadMonitorInfo> findTopCpuTimeThreads(int num) {
        if (!threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        NQuery<ThreadInfo> allThreads = NQuery.of(threads.dumpAllThreads(true, true));
        long[] tids = Arrays.toPrimitive(allThreads.select(ThreadInfo::getThreadId).toArray());
        long[] threadCpuTime = threads.getThreadCpuTime(tids);
//        long[] threadUserTime = threads.getThreadUserTime(tids);
//        return allThreads.select((p, i) -> new ThreadMonitorInfo(p, threadCpuTime[i], threadUserTime[i])).orderByDescending(p -> p.cpuTime).take(num).toList();
        return allThreads.select((p, i) -> new ThreadMonitorInfo(p, threadCpuTime[i])).orderByDescending(p -> p.cpuTime).take(num).toList();
    }
}
