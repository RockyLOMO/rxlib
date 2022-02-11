package org.rx.core;

import org.rx.bean.IntWaterMark;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
    final String poolName;

    public ScheduledThreadPool() {
        this(ThreadPool.DEFAULT_CPU_WATER_MARK, "schedule");
    }

    public ScheduledThreadPool(IntWaterMark cpuWaterMark, String poolName) {
        super(ThreadPool.getResizeQuantity(), ThreadPool.newThreadFactory(poolName));
        this.poolName = poolName;

        ThreadPool.SIZER.register(this, cpuWaterMark);
    }

    @Override
    public String toString() {
        return poolName;
    }
}
