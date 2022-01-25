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
        private ThreadPool pool;
        private final int queueCapacity;
        private final AtomicInteger counter = new AtomicInteger();

        public ThreadQueue(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        @Override
        public boolean isEmpty() {
            return counter.get() == 0;
        }

        @Override
        public int size() {
            return counter.get();
        }

        @SneakyThrows
        @Override
        public boolean offer(T t) {
//            if (t == EMPTY) {
//                return false;
//            }
            IdentityRunnable p = pool.getAs((Runnable) t, false);
            if (p != null && eq(p.flag(), RunFlag.PRIORITY)) {
                incrSize(pool);
                //New thread to execute
                return false;
            }

            boolean isFull = counter.get() >= queueCapacity;
            if (isFull) {
                while (counter.get() >= queueCapacity) {
                    log.warn("Block caller thread[{}] until queue[{}/{}] polled then offer {}", Thread.currentThread().getName(),
                            counter.get(), queueCapacity, t);
                    synchronized (this) {
                        wait();
                    }
                }
                log.debug("Wait poll ok");
            }
            counter.incrementAndGet();
            try {
                return super.offer(t);
            } finally {
                log.debug("queue[{}] offer {}", counter.get(), t);
            }
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
                    log.debug("Notify poll");
                    doNotify();
                }
            }
        }

        @Override
        public T take() throws InterruptedException {
            try {
                return super.take();
            } finally {
                log.debug("Notify take");
                doNotify();
            }
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = super.remove(o);
            if (ok) {
                log.debug("Notify remove");
                doNotify();
            }
            return ok;
        }

        private void doNotify() {
            counter.decrementAndGet();
            synchronized (this) {
                notify();
            }
        }
    }

    public interface IdentityRunnable extends Runnable {
        Object id();

        default RunFlag flag() {
            return RunFlag.DEFAULT;
        }
    }

    static class DynamicSizer implements TimerTask {
        static final long SAMPLING_PERIOD = 3000L;
        static final int SAMPLING_TIMES = 2;
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final HashedWheelTimer timer = new HashedWheelTimer(newThreadFactory("DynamicSizer"), 800L, TimeUnit.MILLISECONDS, 8);
        final Map<ThreadPoolExecutor, BiTuple<IntWaterMark, Integer, Integer>> hold = Collections.synchronizedMap(new WeakHashMap<>(8));

        DynamicSizer() {
            timer.newTimeout(this, SAMPLING_PERIOD, TimeUnit.MILLISECONDS);
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
                timer.newTimeout(this, SAMPLING_PERIOD, TimeUnit.MILLISECONDS);
            }
        }

        private void thread(Decimal cpuLoad, ThreadPoolExecutor pool, BiTuple<IntWaterMark, Integer, Integer> tuple) {
            IntWaterMark waterMark = tuple.left;
            int decrementCounter = tuple.middle;
            int incrementCounter = tuple.right;

            String prefix = pool.toString();
            if (log.isDebugEnabled()) {
                int poolSize = pool.getPoolSize();
                log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                        poolSize, pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);
            }

            if (cpuLoad.gt(waterMark.getHigh())) {
                if (++decrementCounter >= SAMPLING_TIMES) {
                    log.info("{} Threshold={}[{}-{}]% decrement to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrSize(pool));
                    decrementCounter = 0;
                }
            } else {
                decrementCounter = 0;
            }

            if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
                if (++incrementCounter >= SAMPLING_TIMES) {
                    log.info("{} Threshold={}[{}-{}]% increment to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), incrSize(pool));
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
    static final IntWaterMark DEFAULT_CPU_WATER_MARK = new IntWaterMark(
            SystemPropertyUtil.getInt(Constants.CPU_LOW_WATER_MARK, 40),
            SystemPropertyUtil.getInt(Constants.CPU_HIGH_WATER_MARK, 70));
    static final DynamicSizer SIZER = new DynamicSizer();
    static final Runnable EMPTY = () -> {
    };

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

    static int incrSize(ThreadPoolExecutor pool) {
//        pool.getPoolSize()
        int poolSize = pool.getCorePoolSize() + getResizeQuantity();
        if (poolSize > 1000) {
            return 1000;
        }
        pool.setCorePoolSize(poolSize);
        return poolSize;
//        pool.setMaximumPoolSize(maxSize);
//        pool.execute(EMPTY);
    }

    static int decrSize(ThreadPoolExecutor pool) {
        int poolSize = pool.getCorePoolSize() - getResizeQuantity();
        if (poolSize < 4) {
            return 4;
        }
        pool.setCorePoolSize(poolSize);
        return poolSize;
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(CPU_THREADS, Math.floor(CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    @Getter
    private final String poolName;
    private final ConcurrentHashMap<Runnable, Runnable> funcMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Tuple<ReentrantLock, AtomicInteger>> syncRoots = new ConcurrentHashMap<>(8);

    @Override
    public void setMaximumPoolSize(int maximumPoolSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        log.warn("ignore setRejectedExecutionHandler");
    }

    public ThreadPool(String poolName) {
        //computeThreads(1, 2, 1)
        this(SystemPropertyUtil.getInt(Constants.THREAD_POOL_MIN_SIZE, CPU_THREADS + 1),
                SystemPropertyUtil.getInt(Constants.THREAD_POOL_QUEUE_CAPACITY, CPU_THREADS * 32), poolName);
    }

    public ThreadPool(int coreThreads, int queueCapacity, String poolName) {
        this(coreThreads, queueCapacity, DEFAULT_CPU_WATER_MARK, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param coreThreads   最小线程数
     * @param queueCapacity LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int coreThreads, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        super(Math.max(2, coreThreads), Integer.MAX_VALUE, 0, TimeUnit.MILLISECONDS, new ThreadQueue<>(Math.max(1, queueCapacity)), newThreadFactory(poolName), (r, executor) -> {
            if (r == EMPTY) {
                return;
            }
            if (executor.isShutdown()) {
                log.warn("ThreadPool {} is shutdown", poolName);
                return;
            }
            executor.getQueue().offer(r);
        });
        ((ThreadQueue<Runnable>) getQueue()).pool = this;
        this.poolName = poolName;

        setDynamicSize(cpuWaterMark);
    }

    public void setDynamicSize(IntWaterMark cpuWaterMark) {
        if (cpuWaterMark.getLow() < 0) {
            cpuWaterMark.setLow(0);
        }
        if (cpuWaterMark.getHigh() > 100) {
            cpuWaterMark.setHigh(100);
        }
        SIZER.register(this, cpuWaterMark);
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        IdentityRunnable p = null;
        if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
            Object fn = Reflects.readField(r.getClass(), r, "fn");
            if (fn == null) {
                log.warn("{}.fn is null", r);
            } else {
                funcMap.put(r, (Runnable) fn);
                p = as(fn, IdentityRunnable.class);
            }
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
    }

    @Override
    public String toString() {
        return poolName;
//        return super.toString();
    }
}
