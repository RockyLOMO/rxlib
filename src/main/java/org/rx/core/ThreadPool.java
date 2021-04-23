package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.util.concurrent.FastThreadLocalThread;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.InvalidException;

import java.io.Serializable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class DynamicConfig implements Serializable {
        private static final long serialVersionUID = 435663699833833222L;
        private int variable = CPU_THREADS;
        private int minThreshold = 40, maxThreshold = 60;
        private int samplingTimes = 8;
    }

    @RequiredArgsConstructor
    public static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private final int queueCapacity;
        @Setter
        private ThreadPool executor;
        private final AtomicInteger counter = new AtomicInteger();
        private final ManualResetEvent waiter = new ManualResetEvent();

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
                log.debug("{}/{} New thread to execute", poolSize, executor.getMaximumPoolSize());
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

    public interface NamedRunnable extends Runnable {
        String getName();

        default ExecuteFlag getFlag() {
            return ExecuteFlag.Parallel;
        }
    }

    public enum ExecuteFlag {
        Parallel,
        Synchronous,
        Single;
    }

    public static final int CPU_THREADS = Runtime.getRuntime().availableProcessors();

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Global", e));
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(CPU_THREADS, Math.floor(CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    static ThreadFactory newThreadFactory(String nameFormat) {
        return new ThreadFactoryBuilder().setThreadFactory(FastThreadLocalThread::new)
//                .setUncaughtExceptionHandler((thread, e) -> log.error("THREAD", e))
                .setDaemon(true).setNameFormat(nameFormat).build();
    }

    private final AtomicInteger submittedTaskCounter = new AtomicInteger();
    private final ConcurrentHashMap<Runnable, Runnable> funcMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> syncRoot = new ConcurrentHashMap<>();
    @Setter
    @Getter
    private String poolName;
    private BiConsumer<ManagementMonitor, NEventArgs<ManagementMonitor.MonitorInfo>> scheduled;
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

        decrementCounter = new AtomicInteger();
        incrementCounter = new AtomicInteger();
        ManagementMonitor monitor = ManagementMonitor.getInstance();
        monitor.scheduled = combine(App.remove(monitor.scheduled, scheduled), scheduled = (s, e) -> {
            String prefix = String.format("%sMonitor", poolName);
            int cpuLoad = e.getValue().getCpuLoadPercent();
            log.debug("{} PoolSize={}/{} QueueSize={} SubmittedTaskCount={} CpuLoad={}% Threshold={}-{}%", prefix, getPoolSize(), getMaximumPoolSize(), getQueue().size(), getSubmittedTaskCount(),
                    cpuLoad, dynamicConfig.getMinThreshold(), dynamicConfig.getMaxThreshold());

            if (cpuLoad > dynamicConfig.getMaxThreshold()) {
                int c = decrementCounter.incrementAndGet();
                if (c >= dynamicConfig.getSamplingTimes()) {
                    log.debug("{} decrement {} ok", prefix, dynamicConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() - dynamicConfig.getVariable());
                    decrementCounter.set(0);
                } else {
                    log.debug("{} decrementCounter={}", prefix, c);
                }
            } else {
                decrementCounter.set(0);
            }

            if (getQueue().isEmpty()) {
                log.debug("{} increment disabled", prefix);
                return;
            }
            if (cpuLoad < dynamicConfig.getMinThreshold()) {
                int c = incrementCounter.incrementAndGet();
                if (c >= dynamicConfig.getSamplingTimes()) {
                    log.debug("{} increment {} ok", prefix, dynamicConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() + dynamicConfig.getVariable());
                    incrementCounter.set(0);
                } else {
                    log.debug("{} incrementCounter={}", prefix, c);
                }
            } else {
                incrementCounter.set(0);
            }
        });
        return this;
    }

    public ThreadPool() {
        this(CPU_THREADS + 1);
    }

    public ThreadPool(int coreSize) {
        this(coreSize, computeThreads(1, 2, 1), 2, CPU_THREADS * 64, "℞Thread");
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param coreThreads      最小线程数
     * @param maxThreads       最大线程数
     * @param keepAliveMinutes 超出最小线程数的最大线程数存活时间
     * @param queueCapacity    LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     * @param poolName         线程池名称
     */
    public ThreadPool(int coreThreads, int maxThreads, int keepAliveMinutes, int queueCapacity, String poolName) {
        super(coreThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, new ThreadQueue<>(Math.max(1, queueCapacity)),
                newThreadFactory(String.format("%s-%%d", poolName)), (r, executor) -> {
                    if (executor.isShutdown()) {
                        throw new InvalidException("Executor %s is shutdown", poolName);
                    }
                    log.debug("Block caller thread Until offer");
                    executor.getQueue().offer(r);
                });
        ((ThreadQueue<Runnable>) getQueue()).setExecutor(this);
        this.poolName = poolName;
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        NamedRunnable p = tryAs(r);
        if (p != null) {
            if (p.getFlag() == null || p.getFlag() == ExecuteFlag.Parallel) {
                return;
            }
            ReentrantLock locker = syncRoot.computeIfAbsent(p.getName(), k -> new ReentrantLock());
            switch (p.getFlag()) {
                case Single:
                    if (!locker.tryLock()) {
                        throw new InterruptedException(String.format("SingleExecute %s locked by other thread", p.getName()));
                    }
                    log.debug("{} {} tryLock", p.getFlag(), p.getName());
                    break;
                case Synchronous:
                    locker.lock();
                    log.debug("{} {} lock", p.getFlag(), p.getName());
                    break;
            }
        }

        super.beforeExecute(t, r);
    }

    private NamedRunnable tryAs(Runnable command) {
        Runnable r = funcMap.remove(command);
        if (r != null) {
            return as(r, NamedRunnable.class);
        }

        if ((r = command) instanceof CompletableFuture.AsynchronousCompletionTask) {
            r = Reflects.readField(r.getClass(), r, "fn");
            funcMap.put(command, r);
        }
        return as(r, NamedRunnable.class);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        NamedRunnable p = tryAs(r);
        if (p != null) {
            //todo when remove
            ReentrantLock locker = syncRoot.get(p.getName());
            if (locker != null) {
                log.debug("{} {} unlock", p.getFlag(), p.getName());
                locker.unlock();
            }
        }

        super.afterExecute(r, t);
        submittedTaskCounter.decrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
        submittedTaskCounter.incrementAndGet();
        super.execute(command);
    }

    public void offer(Runnable command) {
        log.debug("Block caller thread Until put");
        getQueue().offer(command);
    }

    @SneakyThrows
    public void transfer(Runnable command) {
        log.debug("Block caller thread Until consume");
        ((ThreadQueue<Runnable>) getQueue()).transfer(command);
    }
}
