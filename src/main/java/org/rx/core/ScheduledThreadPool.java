package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;

import java.util.concurrent.ScheduledThreadPoolExecutor;

@Slf4j
public final class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
    final String poolName;

    public ScheduledThreadPool() {
        this(ThreadPool.DEFAULT_CPU_WATER_MARK, "schedule");
    }

    public ScheduledThreadPool(IntWaterMark cpuWaterMark, String poolName) {
        super(ThreadPool.getResizeQuantity(), ThreadPool.newThreadFactory(poolName), (r, executor) -> log.error("scheduler reject"));
        this.poolName = poolName;

        ThreadPool.SIZER.register(this, cpuWaterMark);
    }

    @Override
    public String toString() {
        return poolName;
    }
}
