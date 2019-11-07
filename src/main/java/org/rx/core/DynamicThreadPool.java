package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DynamicThreadPool extends ThreadPool {
    private static final int PercentRatio = 100;
    private static final OperatingSystemMXBean systemMXBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    @Getter
    @Setter
    private int variable = 2;
    @Getter
    @Setter
    private int minThreshold = 40, maxThreshold = 60;
    @Getter
    @Setter
    private int samplingTimes = 10;
    @Getter
    @Setter
    private int samplingDelay = StatisticsDelay;
    private AtomicInteger decrementCounter = new AtomicInteger();
    private AtomicInteger incrementCounter = new AtomicInteger();
    private Future future;

    public int getCpuLoadPercent() {
        return (int) Math.ceil(systemMXBean.getSystemCpuLoad() * PercentRatio);
    }

    public DynamicThreadPool(String poolName) {
        setPoolName(poolName);
        future = Tasks.schedule(() -> {
            if (getQueue().isEmpty()) {
                return;
            }
            int cpuLoad = getCpuLoadPercent();
            log.debug("CurrentCpuLoad={}%% Threshold={}-{}%%", cpuLoad, minThreshold, maxThreshold);
            if (cpuLoad > maxThreshold) {
                if (decrementCounter.incrementAndGet() >= samplingTimes) {
                    setMaximumPoolSize(getMaximumPoolSize() - variable);
                    decrementCounter.set(0);
                }
            } else {
                decrementCounter.set(0);
            }

            if (cpuLoad < minThreshold) {
                if (incrementCounter.incrementAndGet() >= samplingTimes) {
                    setMaximumPoolSize(getMaximumPoolSize() + variable);
                    incrementCounter.set(0);
                }
            } else {
                incrementCounter.set(0);
            }
        }, samplingDelay, samplingDelay, String.format("%sMonitor", poolName));
    }
}
