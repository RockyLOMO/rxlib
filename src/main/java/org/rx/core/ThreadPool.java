package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.management.OperatingSystemMXBean;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Contract.require;

@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class DynamicConfig implements Serializable {
        private int variable = CpuThreads;
        private int minThreshold = 40, maxThreshold = 60;
        private int samplingTimes = 4;
        private int samplingDelay = 2000;
    }

    @RequiredArgsConstructor
    public static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private final int queueCapacity;
        @Setter
        private ThreadPool executor;
        private AtomicInteger counter = new AtomicInteger();
        private ManualResetEvent waiter = new ManualResetEvent();

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public int size() {
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
                counter.incrementAndGet();
                return super.offer(t);
            }

            if (poolSize < executor.getMaximumPoolSize()) {
                log.debug("New thread to execute");
                return false;
            }

            counter.incrementAndGet();
            return super.offer(t);
        }

        @Override
        public T poll(long timeout, TimeUnit unit) throws InterruptedException {
            boolean ok = true;
            try {
                T t = super.poll(timeout, unit);
                ok = t != null;
                return t;
            } catch (InterruptedException e) {
                ok = false;
                throw e;
            } finally {
                if (ok) {
                    log.debug("setPoll() poll");
                    setPoll();
                }
            }
        }

        @Override
        public T take() throws InterruptedException {
            try {
                return super.take();
            } finally {
                log.debug("setPoll() take");
                setPoll();
            }
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = super.remove(o);
            if (ok) {
                log.debug("setPoll() remove");
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
    private static final int PercentRatio = 100;
    private static final OperatingSystemMXBean systemMXBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static int getCpuLoadPercent() {
        return (int) Math.ceil(systemMXBean.getSystemCpuLoad() * PercentRatio);
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(CpuThreads, Math.floor(CpuThreads * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    static ThreadFactory newThreadFactory(String nameFormat) {
        return new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> log.error(thread.getName(), ex))
                .setNameFormat(nameFormat).build();
    }

    private final AtomicInteger submittedTaskCounter = new AtomicInteger();
    @Setter
    @Getter
    private String poolName;
    private ScheduledExecutorService monitorTimer;
    private ScheduledFuture monitorFuture;
    private AtomicInteger decrementCounter;
    private AtomicInteger incrementCounter;

    public int getSubmittedTaskCount() {
        return submittedTaskCounter.get();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        log.warn("ignore setRejectedExecutionHandler");
    }

    public synchronized ThreadPool statistics(DynamicConfig dynamicConfig) {
        require(dynamicConfig);

        if (monitorTimer == null) {
            monitorTimer = Executors.newSingleThreadScheduledExecutor(newThreadFactory(String.format("%sMonitor", poolName)));
        }
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }
        decrementCounter = new AtomicInteger();
        incrementCounter = new AtomicInteger();
        monitorFuture = monitorTimer.scheduleWithFixedDelay(() -> {
            int cpuLoad = getCpuLoadPercent();
            log.info("PoolSize={}/{} QueueSize={} SubmittedTaskCount={} CpuLoad={}% Threshold={}-{}%", getPoolSize(), getMaximumPoolSize(), getQueue().size(), getSubmittedTaskCount(),
                    cpuLoad, dynamicConfig.getMinThreshold(), dynamicConfig.getMaxThreshold());

            if (cpuLoad > dynamicConfig.getMaxThreshold()) {
                int c = decrementCounter.incrementAndGet();
                if (c >= dynamicConfig.getSamplingTimes()) {
                    log.debug("DynamicPoolSize decrement {} ok", dynamicConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() - dynamicConfig.getVariable());
                    decrementCounter.set(0);
                } else {
                    log.debug("DynamicPoolSize decrementCounter={}", c);
                }
            } else {
                decrementCounter.set(0);
            }

            if (getQueue().isEmpty()) {
                log.debug("DynamicPoolSize increment disabled");
                return;
            }
            if (cpuLoad < dynamicConfig.getMinThreshold()) {
                int c = incrementCounter.incrementAndGet();
                if (c >= dynamicConfig.getSamplingTimes()) {
                    log.debug("DynamicPoolSize increment {} ok", dynamicConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() + dynamicConfig.getVariable());
                    incrementCounter.set(0);
                } else {
                    log.debug("DynamicPoolSize incrementCounter={}", c);
                }
            } else {
                incrementCounter.set(0);
            }
        }, dynamicConfig.getSamplingDelay(), dynamicConfig.getSamplingDelay(), TimeUnit.MILLISECONDS);
        return this;
    }

    public ThreadPool() {
        this(CpuThreads + 1, computeThreads(1, 2, 1), 4, CpuThreads * 64, "ThreadPool");
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
        this.poolName = poolName;
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);
        submittedTaskCounter.decrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
        submittedTaskCounter.incrementAndGet();
        super.execute(command);
    }

    public void put(Runnable command) {
        log.debug("Block caller thread Until put");
        getQueue().offer(command);
    }

    @SneakyThrows
    public void transfer(Runnable command) {
        log.debug("Block caller thread Until consume");
        ((ThreadQueue<Runnable>) getQueue()).transfer(command);
    }
}
