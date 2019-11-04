package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DynamicThreadPool extends ThreadPool {
    private static final int Percent = 100;
    private static final int ThreadCount = 2;
    private OperatingSystemMXBean systemMXBean;
    private ScheduledExecutorService statisticsTimer;
    @Getter
    @Setter
    private int criticalPercent = 40;
    @Getter
    @Setter
    private int samplingTimes = 10;
    private int incrementCounter;
    private int decrementCounter;

    public int getCpuLoadPercent() {
        return (int) Math.ceil(systemMXBean.getSystemCpuLoad() * Percent);
    }

    public DynamicThreadPool(String poolName) {
        setPoolName(poolName);
        systemMXBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        statisticsTimer = Executors.newSingleThreadScheduledExecutor(newThreadFactory(String.format("%sMonitor", poolName)));
        long delay = 1000;
        statisticsTimer.scheduleWithFixedDelay(() -> {
//            if (getQueue().isEmpty()) {
//                return;
//            }
            if (getPoolSize() <= getCorePoolSize()) {
                return;
            }

            int currentCpuLoad = getCpuLoadPercent();
            log.debug("CurrentCpuLoad={}%% Critical={}%%", currentCpuLoad, criticalPercent);
            if (currentCpuLoad > 99) {
                if (++decrementCounter == samplingTimes) {
                    setMaximumPoolSize(getMaximumPoolSize() - ThreadCount);
                    decrementCounter = 0;
                }
            } else {
                decrementCounter = 0;
            }
            if (currentCpuLoad < criticalPercent) {
                if (++incrementCounter == samplingTimes) {
                    setMaximumPoolSize(getMaximumPoolSize() + ThreadCount);
                    incrementCounter = 0;
                }
            } else {
                incrementCounter = 0;
            }
        }, delay, delay, TimeUnit.MILLISECONDS);
    }
}
