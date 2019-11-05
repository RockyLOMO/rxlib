package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @RequiredArgsConstructor
    private static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private final int queueCapacity;
        @Setter
        private ThreadPool executor;
        private AtomicInteger counter = new AtomicInteger();
        private ManualResetEvent waiter = new ManualResetEvent();

        @Override
        public boolean isEmpty() {
//            return super.isEmpty();
            return size() == 0;
        }

        @Override
        public int size() {
//            return super.size();
            return counter.get();
        }

        @Override
        public boolean offer(T t) {
            int poolSize = executor.getPoolSize();
            if (poolSize == executor.getMaximumPoolSize()) {
                if (counter.incrementAndGet() > queueCapacity) {
                    do {
                        log.debug("Queue is full & Wait poll");
                        waiter.waitOne();
                        waiter.reset();
                    }
                    while (counter.get() > queueCapacity);
                    log.debug("Wait poll ok");
                }
                return super.offer(t);
            }

            if (executor.getSubmittedTaskCount() < poolSize) {
                log.debug("Idle thread to execute");
                return super.offer(t);
            }

            if (poolSize < executor.getMaximumPoolSize()) {
                log.debug("New thread to execute");
                return false;
            }

            return super.offer(t);
        }

        @Override
        public T poll(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                return super.poll(timeout, unit);
            } finally {
                setPoll();
            }
        }

        @Override
        public T take() throws InterruptedException {
            try {
                return super.take();
            } finally {
                setPoll();
            }
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = super.remove(o);
            if (ok) {
                setPoll();
            }
            return ok;
        }

        private void setPoll() {
            counter.decrementAndGet();
            waiter.set();
        }
    }

    public static final int CpuThreads = Runtime.getRuntime().availableProcessors();
    public static final int MaxThreads = CpuThreads * 100000;

    public static int computeThreads(long ioTime) {
        return computeThreads(ioTime, 1);
    }

    public static int computeThreads(long ioTime, long cpuTime) {
        return (int) Math.max(CpuThreads, Math.floor(CpuThreads * (1 + ((double) ioTime / cpuTime))));
    }

    static ThreadFactory newThreadFactory(String nameFormat) {
        return new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> log.error(thread.getName(), ex))
                .setNameFormat(nameFormat).build();
    }

    private AtomicInteger submittedTaskCounter = new AtomicInteger();
    private int submittedTaskCapacity;
    @Setter
    @Getter
    private String poolName;
    private ScheduledExecutorService monitorTimer;

    public int getSubmittedTaskCount() {
        return submittedTaskCounter.get();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        log.warn("ignore setRejectedExecutionHandler");
    }

    public ThreadPool printStatistics(long delay) {
        if (monitorTimer == null) {
            monitorTimer = Executors.newSingleThreadScheduledExecutor(newThreadFactory(String.format("%sMonitor", poolName)));
        }
        monitorTimer.scheduleWithFixedDelay(() -> {
            log.info("PoolSize={} QueueSize={} SubmittedTaskCount={} {}", getPoolSize(), getQueue().size(), getSubmittedTaskCount(), getActiveCount());
        }, delay, delay, TimeUnit.MILLISECONDS);
        return this;
    }

    public ThreadPool() {
        this(CpuThreads + 1, CpuThreads * 2, 4, CpuThreads * 64, "ThreadPool");
    }

    public ThreadPool(int coreThreads, int maxThreads, int keepAliveMinutes, int queueCapacity, String poolName) {
        super(coreThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, new ThreadQueue<>(Math.max(1, queueCapacity)),
                newThreadFactory(String.format("%s-%%d", poolName)), (r, executor) -> {
                    if (executor.isShutdown()) {
                        throw new InvalidOperationException("Executor %s is shutdown", poolName);
                    }
                    log.debug("Block caller thread Until offer");
                    executor.getQueue().offer(r);
                });
        ((ThreadQueue) getQueue()).setExecutor(this);
        submittedTaskCapacity = maxThreads + queueCapacity;
        this.poolName = poolName;
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);
        submittedTaskCounter.decrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
        int count = submittedTaskCounter.incrementAndGet();
        if (count > submittedTaskCapacity) {
//            submittedTaskCounter.decrementAndGet();
            getRejectedExecutionHandler().rejectedExecution(command, this);
            return;
        }

        super.execute(command);
    }
}
