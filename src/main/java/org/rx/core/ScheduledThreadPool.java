package org.rx.core;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
    final String poolName;

    public ScheduledThreadPool() {
        super(RxConfig.INSTANCE.threadPool.scheduleInitSize, ThreadPool.newThreadFactory("schedule"));
        this.poolName = "schedule";

        ThreadPool.SIZER.register(this, ThreadPool.DEFAULT_CPU_WATER_MARK);
    }

    @Override
    public String toString() {
        return poolName;
    }
}
