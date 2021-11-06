package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;

import java.util.concurrent.ScheduledThreadPoolExecutor;

@Slf4j
public final class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
    final String poolName;
//    final int minSize;

    public ScheduledThreadPool() {
        this(ThreadPool.DEFAULT_CPU_WATER_MARK, "schedule");
    }

    public ScheduledThreadPool(IntWaterMark cpuWaterMark, String poolName) {
        super(ThreadPool.RESIZE_QUANTITY, ThreadPool.newThreadFactory(poolName), (r, executor) -> log.error("scheduler reject"));
//        setMaximumPoolSize(maxSize);
//        this.minSize = minSize;
        this.poolName = poolName;

        ThreadPool.SIZER.register(this, cpuWaterMark);
    }

//    @Override
//    public String toString() {
//        return poolName;
//    }

    //    @Override
//    protected void beforeExecute(Thread t, Runnable r) {
//        int size = getCorePoolSize();
//        if (size < getMaximumPoolSize() && getActiveCount() >= size) {
//            size = Math.max(ThreadPool.RESIZE_QUANTITY, ++size);
//            setCorePoolSize(size);
//            log.debug("grow pool size {}", size);
//        }
//
//        super.beforeExecute(t, r);
//    }

//    @Override
//    protected void afterExecute(Runnable r, Throwable t) {
//        super.afterExecute(r, t);
//
//        int size = getCorePoolSize();
//        float idle;
//        if (size > minSize && (idle = (float) getActiveCount() / size) <= 0.5f) {
//            setCorePoolSize(--size);
//            log.debug("reduce pool size {}, idle={}", size, idle);
//        }
//    }
}
