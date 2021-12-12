package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.management.OperatingSystemMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.BiTuple;
import org.rx.bean.Decimal;
import org.rx.bean.IntWaterMark;
import org.rx.bean.Tuple;
import org.rx.exception.ExceptionHandler;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.core.App.*;

@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    public static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private static final long serialVersionUID = -1832603760465558822L;
        private ThreadPool pool;
        private int queueCapacity = Integer.MAX_VALUE;
        private final AtomicInteger counter = new AtomicInteger();

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
            IdentityRunnable p = pool.getAs((Runnable) t, false);
            if (p != null && p.flag() != null) {
                switch (p.flag()) {
                    case TRANSFER:
                        log.debug("Block caller thread until queue take");
                        transfer(t);
                        return true;
                    case PRIORITY:
                        incrSize(pool, pool.getMaximumPoolSize() + getResizeQuantity());
                        return false;
                }
            }

            int poolSize = pool.getPoolSize();
            if (poolSize == pool.getMaximumPoolSize()) {
                while (counter.get() >= queueCapacity) {
                    log.warn("Block caller thread[{}] until queue[{}/{}] polled", Thread.currentThread().getName(),
                            counter.get(), queueCapacity);
                    synchronized (this) {
                        wait();
                    }
                }
                log.debug("Wait poll ok");
                counter.incrementAndGet();
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
            synchronized (this) {
                notify();
            }
        }
    }

    public interface IdentityRunnable extends Runnable {
        Object id();

        default RunFlag flag() {
            return RunFlag.CONCURRENT;
        }
    }

    static class DynamicSizer implements TimerTask {
        static final int SAMPLING_TIMES = 4;
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final HashedWheelTimer timer = new HashedWheelTimer(newThreadFactory("DynamicSizer"), 800L, TimeUnit.MILLISECONDS, 8);
        final Map<ThreadPoolExecutor, BiTuple<IntWaterMark, Integer, Integer>> hold = Collections.synchronizedMap(new WeakHashMap<>(8));

        DynamicSizer() {
            timer.newTimeout(this, 1000L, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            try {
                Decimal cpuLoad = Decimal.valueOf(os.getSystemCpuLoad() * 100);
                for (Map.Entry<ThreadPoolExecutor, BiTuple<IntWaterMark, Integer, Integer>> entry : hold.entrySet()) {
                    ThreadPoolExecutor pool = entry.getKey();
                    if (pool instanceof ScheduledExecutorService) {
                        scheduledThread(cpuLoad, pool, entry.getValue());
                        continue;
                    }
                    thread(cpuLoad, pool, entry.getValue());
                }
            } finally {
                timer.newTimeout(this, 1000L, TimeUnit.MILLISECONDS);
            }
        }

        private void thread(Decimal cpuLoad, ThreadPoolExecutor pool, BiTuple<IntWaterMark, Integer, Integer> tuple) {
            IntWaterMark waterMark = tuple.left;
            int decrementCounter = tuple.middle;
            int incrementCounter = tuple.right;

            String prefix = pool.toString();
            int maxSize = pool.getMaximumPoolSize();
            int resizeQuantity = getResizeQuantity();
            log.debug("{} PoolSize={}/{} QueueSize={} Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                    pool.getPoolSize(), maxSize, pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);

            if (cpuLoad.gt(waterMark.getHigh())) {
                if (++decrementCounter >= SAMPLING_TIMES) {
                    maxSize -= resizeQuantity;
                    if (maxSize >= pool.getCorePoolSize()) {
                        log.info("{} Threshold={}[{}-{}]% decrement to {}", prefix,
                                cpuLoad, waterMark.getLow(), waterMark.getHigh(), maxSize);
                        pool.setMaximumPoolSize(maxSize);
                        decrementCounter = 0;
                    }
                }
            } else {
                decrementCounter = 0;
            }

            if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
                if (++incrementCounter >= SAMPLING_TIMES) {
                    maxSize += resizeQuantity;
                    log.info("{} Threshold={}[{}-{}]% increment to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), maxSize);
                    incrSize(pool, maxSize);
                    incrementCounter = 0;
                }
            } else {
                incrementCounter = 0;
            }

            tuple.middle = decrementCounter;
            tuple.right = incrementCounter;
        }

        private void scheduledThread(Decimal cpuLoad, ThreadPoolExecutor pool, BiTuple<IntWaterMark, Integer, Integer> tuple) {
            IntWaterMark waterMark = tuple.left;
            int decrementCounter = tuple.middle;
            int incrementCounter = tuple.right;

            String prefix = pool.toString();
            int active = pool.getActiveCount();
            int size = pool.getCorePoolSize();
            float idle = (float) active / size * 100;
            int resizeQuantity = getResizeQuantity();
            log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% idle={} de/incrementCounter={}/{}", prefix,
                    pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrementCounter, incrementCounter);

            if (size > resizeQuantity && (idle <= waterMark.getHigh() || cpuLoad.gt(waterMark.getHigh()))) {
                if (++decrementCounter >= SAMPLING_TIMES) {
                    size -= resizeQuantity;
                    log.info("{} Threshold={}[{}-{}]% idle={} decrement to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, size);
                    pool.setCorePoolSize(size);
                    decrementCounter = 0;
                }
            } else {
                decrementCounter = 0;
            }

            if (active >= size && cpuLoad.lt(waterMark.getLow())) {
                if (++incrementCounter >= SAMPLING_TIMES) {
                    size += resizeQuantity;
                    log.info("{} Threshold={}[{}-{}]% increment to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), size);
                    pool.setCorePoolSize(size);
                    incrementCounter = 0;
                }
            } else {
                incrementCounter = 0;
            }

            tuple.middle = decrementCounter;
            tuple.right = incrementCounter;
        }

        public void register(ThreadPoolExecutor pool, IntWaterMark cpuWaterMark) {
            if (cpuWaterMark == null) {
                return;
            }

            hold.put(pool, BiTuple.of(cpuWaterMark, 0, 0));
        }
    }

    public static final int CPU_THREADS = Runtime.getRuntime().availableProcessors();
    static final String POOL_NAME_PREFIX = "℞Threads-";
    static final int DEFAULT_KEEP_ALIVE_MINUTES = 20;
    static final IntWaterMark DEFAULT_CPU_WATER_MARK = new IntWaterMark(40, 60);
    static final DynamicSizer SIZER = new DynamicSizer();

    static int getResizeQuantity() {
        return SystemPropertyUtil.getInt(Constants.THREAD_POOL_RESIZE_QUANTITY, 2);
    }

    static {
        Thread.setDefaultUncaughtExceptionHandler(Container.get(ExceptionHandler.class));
    }

    static ThreadFactory newThreadFactory(String name) {
        return new ThreadFactoryBuilder().setThreadFactory(FastThreadLocalThread::new)
//                .setUncaughtExceptionHandler(ExceptionHandler.INSTANCE) //跟上面重复
                .setDaemon(true).setNameFormat(String.format("%s%s-%%d", POOL_NAME_PREFIX, name)).build();
    }

    static void incrSize(ThreadPoolExecutor pool, int maxSize) {
        pool.setMaximumPoolSize(maxSize);
        pool.execute(() -> {
        });
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(CPU_THREADS, Math.floor(CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    @Getter
    private final String poolName;
    private final AtomicInteger submittedTaskCounter = new AtomicInteger();
    private final ConcurrentHashMap<Runnable, Runnable> funcMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Tuple<ReentrantLock, AtomicInteger>> syncRoots = new ConcurrentHashMap<>(8);

    public int getSubmittedTaskCount() {
        return submittedTaskCounter.get();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        log.warn("ignore setRejectedExecutionHandler");
    }

    public ThreadPool(String poolName) {
        this(SystemPropertyUtil.getInt(Constants.THREAD_POOL_MIN_SIZE, CPU_THREADS + 1),
                SystemPropertyUtil.getInt(Constants.THREAD_POOL_MAX_SIZE, computeThreads(1, 2, 1)),
                SystemPropertyUtil.getInt(Constants.THREAD_POOL_QUEUE_CAPACITY, CPU_THREADS * 16), poolName);
    }

    public ThreadPool(int coreThreads, int maxThreads, int queueCapacity, String poolName) {
        this(coreThreads, maxThreads, DEFAULT_KEEP_ALIVE_MINUTES, queueCapacity, DEFAULT_CPU_WATER_MARK, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param coreThreads      最小线程数
     * @param maxThreads       最大线程数
     * @param keepAliveMinutes 超出最小线程数的最大线程数存活时间
     * @param queueCapacity    LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int coreThreads, int maxThreads, int keepAliveMinutes, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        super(coreThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, new ThreadQueue<>(), newThreadFactory(poolName), (r, executor) -> {
            if (executor.isShutdown()) {
                log.warn("ThreadPool {} is shutdown", poolName);
                return;
            }
            executor.getQueue().offer(r);
        });
        ((ThreadQueue<Runnable>) getQueue()).pool = this;
        this.poolName = poolName;

        setQueueCapacity(queueCapacity);
        setDynamicSize(cpuWaterMark);
    }

    public void setQueueCapacity(int queueCapacity) {
        ((ThreadQueue<Runnable>) getQueue()).queueCapacity = Math.max(1, queueCapacity);
    }

    public void setDynamicSize(IntWaterMark cpuWaterMark) {
        SIZER.register(this, cpuWaterMark);
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        IdentityRunnable p = null;
        if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
            Object fn = Reflects.readField(r.getClass(), r, "fn");
            if (fn == null) {
                Field field = Reflects.getFields(r.getClass()).firstOrDefault(x -> Runnable.class.isAssignableFrom(x.getType()));
                log.warn("{}.fn is null, field={}", r, field);
            } else {
                funcMap.put(r, (Runnable) fn);
            }
            p = as(fn, IdentityRunnable.class);
        }
        if (p == null) {
            p = as(r, IdentityRunnable.class);
        }
        if (p != null && p.flag() != null) {
            switch (p.flag()) {
                case SINGLE: {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(p.id());
                    if (!locker.left.tryLock()) {
                        throw new InterruptedException(String.format("SingleScope %s locked by other thread", p.id()));
                    }
                    locker.right.incrementAndGet();
                    log.debug("{} {} tryLock", p.flag(), p.id());
                }
                break;
                case SYNCHRONIZED: {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(p.id());
                    locker.right.incrementAndGet();
                    locker.left.lock();
                    log.debug("{} {} lock", p.flag(), p.id());
                }
                break;
            }
        }

        super.beforeExecute(t, r);
    }

    private Tuple<ReentrantLock, AtomicInteger> getLocker(Object id) {
        return syncRoots.computeIfAbsent(id, k -> Tuple.of(new ReentrantLock(), new AtomicInteger()));
    }

    private IdentityRunnable getAs(Runnable command, boolean remove) {
        Runnable r = remove ? funcMap.remove(command) : funcMap.get(command);
        return as(r, IdentityRunnable.class);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        IdentityRunnable p = getAs(r, true);
        if (p != null) {
            Tuple<ReentrantLock, AtomicInteger> locker = syncRoots.get(p.id());
            if (locker != null) {
                log.debug("{} {} unlock", p.flag(), p.id());
                locker.left.unlock();
                if (locker.right.decrementAndGet() <= 0) {
                    syncRoots.remove(p.id());
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

    @Override
    public String toString() {
        return poolName;
//        return super.toString();
    }
}
