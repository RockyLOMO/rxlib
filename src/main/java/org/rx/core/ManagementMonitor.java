package org.rx.core;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.bean.FlagsEnum;

import java.io.File;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.function.BiConsumer;

import static org.rx.core.App.cacheKey;

//JMX监控JVM，K8S监控网络，这里简单处理Thread相关
public class ManagementMonitor implements EventTarget<ManagementMonitor> {
    private static final double PERCENT = 100.0D, k = 1024, m = k * 1024, g = m * 1024, t = g * 1024;
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
            return formatSize(usedSpace);
        }

        public String getTotalString() {
            return formatSize(totalSpace);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class ThreadMonitorInfo {
        private final ThreadInfo threadInfo;
        private final long cpuTime;
//        private final long userTime;

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder(threadInfo.toString());
            int i = s.indexOf("\n");
//            s.insert(i, String.format(" cpuTime=%s userTime=%s", cpuTime, userTime));
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
            return formatSize(usedMemory);
        }

        public String getTotalMemoryString() {
            return formatSize(totalMemory);
        }

        public DiskMonitorInfo getSummedDisk() {
            return disks.groupBy(p -> true, (p, x) -> new DiskMonitorInfo("/", (long) x.sum(y -> y.usedSpace), (long) x.sum(y -> y.totalSpace))).first();
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

    public volatile BiConsumer<ManagementMonitor, NEventArgs<MonitorInfo>> scheduled, cpuWarning, memoryWarning, diskWarning;
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
            raiseEvent(scheduled, args);
            if (args.getValue().getCpuLoad() >= cpuWarningThreshold) {
                raiseEvent(cpuWarning, args);
            }
            if (args.getValue().getUsedMemoryPercent() >= memoryWarningThreshold) {
                raiseEvent(memoryWarning, args);
            }
            for (DiskMonitorInfo disk : args.getValue().getDisks()) {
                if (disk.getUsedPercent() >= diskWarningThreshold) {
                    raiseEvent(diskWarning, args);
                }
            }
        }, 5000);
    }

    public MonitorInfo getInfo() {
        long totalMemory = os.getTotalPhysicalMemorySize();
        return new MonitorInfo(os.getAvailableProcessors(), os.getSystemCpuLoad(), threads.getThreadCount(),
                totalMemory - os.getFreePhysicalMemorySize(), totalMemory,
                Cache.getOrSet(cacheKey("getBean"), k -> NQuery.of(File.listRoots()).select(p -> new DiskMonitorInfo(p.getPath(), p.getTotalSpace() - p.getFreeSpace(), p.getTotalSpace())), Cache.LOCAL_CACHE));
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
