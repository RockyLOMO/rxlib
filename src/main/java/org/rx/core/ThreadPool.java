package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class ThreadPool extends ThreadPoolExecutor {
    @RequiredArgsConstructor
    private static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private final int queueCapacity;
        @Setter
        private ThreadPool executor;
        private AtomicInteger counter = new AtomicInteger();
        private ManualResetEvent waiter = new ManualResetEvent();

        @Override
        public boolean offer(T t) {
            int poolSize = executor.getPoolSize();
            if (poolSize == executor.getMaximumPoolSize()) {
                while (counter.incrementAndGet() > queueCapacity) {
                    log.debug("Queue is full & Wait poll");
                    waiter.waitOne();
                    waiter.reset();
                }
                log.debug("Wait poll ok");
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
    private AtomicInteger submittedTaskCounter = new AtomicInteger();
    private int submittedTaskCapacity;

    public int getSubmittedTaskCount() {
        return submittedTaskCounter.get();
    }

    public ThreadPool() {
        this(CpuThreads, CpuThreads * 2, 4, CpuThreads * 16);
    }

    public ThreadPool(int coreThreads, int maxThreads, int keepAliveMinutes, int queueCapacity) {
        super(coreThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, new ThreadQueue<>(queueCapacity),
                new ThreadFactoryBuilder().setDaemon(true)
                        .setUncaughtExceptionHandler((thread, ex) -> log.error(thread.getName(), ex))
                        .setNameFormat("ThreadPool-%d").build(), (r, executor) -> {
                    if (executor.isShutdown()) {
                        log.debug("Executor is shutdown, caller thread to execute");
                        r.run();
                        return;
                    }
                    log.debug("Block caller thread Until offer");
                    executor.getQueue().offer(r);
                });
        ((ThreadQueue) getQueue()).setExecutor(this);
        submittedTaskCapacity = maxThreads + queueCapacity;
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
            submittedTaskCounter.decrementAndGet();
            getRejectedExecutionHandler().rejectedExecution(command, this);
            return;
        }

        super.execute(command);
    }
}
