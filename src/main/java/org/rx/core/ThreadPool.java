package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.util.concurrent.FastThreadLocalThread;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;
import org.rx.bean.Tuple;
import org.rx.core.exception.InvalidException;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class WaterMarkConfig extends IntWaterMark {
        private static final long serialVersionUID = 435663699833833222L;
        private int variable = CPU_THREADS;
        private int samplingTimes = 8;

        public WaterMarkConfig() {
            this(40, 60);
        }

        public WaterMarkConfig(int minThreshold, int maxThreshold) {
            super(minThreshold, maxThreshold);
        }
    }

    @RequiredArgsConstructor
    public static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private final int queueCapacity;
        @Setter
        private ThreadPool pool;
        private final AtomicInteger counter = new AtomicInteger();
        private final ManualResetEvent syncRoot = new ManualResetEvent();

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public int size() {
            return counter.get();
        }

        @SneakyThrows
        @Override
        public boolean offer(T t) {
            NamedRunnable p = pool.getAs((Runnable) t, false);
            if (p != null && p.getFlag() != null) {
                switch (p.getFlag()) {
                    case TRANSFER:
                        log.debug("Block caller thread until queue take");
                        transfer(t);
                        return true;
                    case PRIORITY:
                        pool.setMaximumPoolSize(pool.getMaximumPoolSize() + 2);
                        return false;
                }
            }

            int poolSize = pool.getPoolSize();
            if (poolSize == pool.getMaximumPoolSize()) {
                if (counter.incrementAndGet() > queueCapacity) {
                    do {
                        log.debug("Queue is full & Wait poll");
                        syncRoot.waitOne();
                        syncRoot.reset();
                    }
                    while (counter.get() > queueCapacity);
                    log.debug("Wait poll ok");
                }
                return super.offer(t);
            }

            if (pool.getSubmittedTaskCount() < poolSize) {
                log.debug("Idle thread to execute");
                counter.incrementAndGet();
                return super.offer(t);
            }

            if (poolSize < pool.getMaximumPoolSize()) {
                log.debug("{}/{} New thread to execute", poolSize, pool.getMaximumPoolSize());
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
            syncRoot.set();
        }
    }

    public interface NamedRunnable extends Runnable {
        String getName();

        default RunFlag getFlag() {
            return RunFlag.CONCURRENT;
        }
    }

    public static final int CPU_THREADS = Runtime.getRuntime().availableProcessors();
    private static final String POOL_NAME_PREFIX = "℞Threads-";

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> App.log("Global", e));
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

    @Getter
    private final String poolName;
    private final AtomicInteger submittedTaskCounter = new AtomicInteger();
    private final ConcurrentHashMap<Runnable, Runnable> funcMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Tuple<ReentrantLock, AtomicInteger>> syncRoot = new ConcurrentHashMap<>(8);
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

    public synchronized ThreadPool statistics(@NonNull ThreadPool.WaterMarkConfig waterMarkConfig) {
        decrementCounter = new AtomicInteger();
        incrementCounter = new AtomicInteger();
        ManagementMonitor monitor = ManagementMonitor.getInstance();
        monitor.scheduled = combine(App.remove(monitor.scheduled, scheduled), scheduled = (s, e) -> {
            String prefix = String.format("%s%sMonitor", POOL_NAME_PREFIX, poolName);
            int cpuLoad = e.getValue().getCpuLoadPercent();
            log.debug("{} PoolSize={}/{} QueueSize={} SubmittedTaskCount={} CpuLoad={}% Threshold={}-{}%", prefix, getPoolSize(), getMaximumPoolSize(), getQueue().size(), getSubmittedTaskCount(),
                    cpuLoad, waterMarkConfig.getLow(), waterMarkConfig.getHigh());

            if (cpuLoad > waterMarkConfig.getHigh()) {
                int c = decrementCounter.incrementAndGet();
                if (c >= waterMarkConfig.getSamplingTimes()) {
                    log.debug("{} decrement {} ok", prefix, waterMarkConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() - waterMarkConfig.getVariable());
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
            if (cpuLoad < waterMarkConfig.getLow()) {
                int c = incrementCounter.incrementAndGet();
                if (c >= waterMarkConfig.getSamplingTimes()) {
                    log.debug("{} increment {} ok", prefix, waterMarkConfig.getVariable());
                    setMaximumPoolSize(getMaximumPoolSize() + waterMarkConfig.getVariable());
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

    public ThreadPool(int coreThreads, String poolName) {
        this(coreThreads, computeThreads(1, 2, 1), 30, CPU_THREADS * 16, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param coreThreads      最小线程数
     * @param maxThreads       最大线程数
     * @param keepAliveMinutes 超出最小线程数的最大线程数存活时间
     * @param queueCapacity    LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int coreThreads, int maxThreads, int keepAliveMinutes, int queueCapacity, String poolName) {
        super(coreThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, new ThreadQueue<>(Math.max(1, queueCapacity)),
                newThreadFactory(String.format("%s%s-%%d", POOL_NAME_PREFIX, poolName)), (r, executor) -> {
                    if (executor.isShutdown()) {
                        throw new InvalidException("ThreadPool %s is shutdown", poolName);
                    }
                    log.debug("Block caller thread until queue offer");
                    executor.getQueue().offer(r);
                });
        ((ThreadQueue<Runnable>) getQueue()).setPool(this);
        this.poolName = poolName;
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        NamedRunnable p = null;
        if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
            Object fn = Reflects.readField(r.getClass(), r, "fn");
            if (fn == null) {
                Field field = Reflects.getFields(r.getClass()).firstOrDefault(x -> Runnable.class.isAssignableFrom(x.getType()));
                log.warn("{}.fn is null, field={}", r, field);
            } else {
                funcMap.put(r, (Runnable) fn);
            }
            p = as(fn, NamedRunnable.class);
        }
        if (p == null) {
            p = as(r, NamedRunnable.class);
        }
        if (p != null && p.getFlag() != null) {
            switch (p.getFlag()) {
                case SINGLE: {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(p.getName());
                    if (!locker.left.tryLock()) {
                        throw new InterruptedException(String.format("SingleScope %s locked by other thread", p.getName()));
                    }
                    locker.right.incrementAndGet();
                    log.debug("{} {} tryLock", p.getFlag(), p.getName());
                }
                break;
                case SYNCHRONIZED: {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(p.getName());
                    locker.right.incrementAndGet();
                    locker.left.lock();
                    log.debug("{} {} lock", p.getFlag(), p.getName());
                }
                break;
            }
        }

        super.beforeExecute(t, r);
    }

    private Tuple<ReentrantLock, AtomicInteger> getLocker(String name) {
        return syncRoot.computeIfAbsent(name, k -> Tuple.of(new ReentrantLock(), new AtomicInteger()));
    }

    private NamedRunnable getAs(Runnable command, boolean remove) {
        Runnable r = remove ? funcMap.remove(command) : funcMap.get(command);
        return as(r, NamedRunnable.class);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        NamedRunnable p = getAs(r, true);
        if (p != null) {
            Tuple<ReentrantLock, AtomicInteger> locker = syncRoot.get(p.getName());
            if (locker != null) {
                log.debug("{} {} unlock", p.getFlag(), p.getName());
                locker.left.unlock();
                if (locker.right.decrementAndGet() <= 0) {
                    syncRoot.remove(p.getName());
                }
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
        log.debug("Block caller thread until queue offer");
        getQueue().offer(command);
    }

    @SneakyThrows
    public void transfer(Runnable command) {
        log.debug("Block caller thread until queue take");
        ((ThreadQueue<Runnable>) getQueue()).transfer(command);
    }
}
