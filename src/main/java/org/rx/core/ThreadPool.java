package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.management.OperatingSystemMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.*;
import org.rx.exception.ExceptionHandler;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
//            Task<?> p = pool.getAs((Runnable) t, false);
//            if (p != null && p.flags.has(RunFlag.PRIORITY)) {
//                incrSize(pool);
//                //New thread to execute
//                return false;
//            }

            boolean isFull = counter.get() >= queueCapacity;
            if (isFull) {
                boolean logged = false;
                while (counter.get() >= queueCapacity) {
                    if (!logged) {
                        log.warn("Block caller thread[{}] until queue[{}/{}] polled then offer {}", Thread.currentThread().getName(),
                                counter.get(), queueCapacity, t);
                        logged = true;
                    }
                    synchronized (this) {
                        wait(500);
                    }
                }
                log.debug("Wait poll ok");
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

    static class Task<T> implements Runnable, Callable<T>, Supplier<T> {
        final InternalThreadLocalMap parent;
        final Object id;
        final FlagsEnum<RunFlag> flags;
        final Func<T> fn;

        Task(Object id, FlagsEnum<RunFlag> flags, Func<T> fn) {
            if (flags == null) {
                flags = RunFlag.NONE.flags();
            }
            if (ENABLE_INHERIT_THREAD_LOCALS) {
                flags.add(RunFlag.INHERIT_THREAD_LOCALS);
            }

            this.id = id;
            this.flags = flags;
            parent = flags.has(RunFlag.INHERIT_THREAD_LOCALS) ? InternalThreadLocalMap.getIfSet() : null;
            this.fn = fn;
        }

        @SneakyThrows
        @Override
        public T call() {
            try {
                return fn.invoke();
            } catch (Throwable e) {
                Container.get(ExceptionHandler.class).uncaughtException(toString(), e);
//                return null;
                throw e;
            }
        }

        @Override
        public void run() {
            call();
        }

        @Override
        public T get() {
            return call();
        }

        @Override
        public String toString() {
            return String.format("Task-%s[%s]", isNull(id, 0), flags);
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
                log.debug("{} PoolSize={}+[{}] Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                        pool.getPoolSize(), pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);
            }

            if (cpuLoad.gt(waterMark.getHigh())) {
                if (++decrementCounter >= SAMPLING_TIMES) {
                    log.info("{} PoolSize={}+[{}] Threshold={}[{}-{}]% decrement to {}", prefix,
                            pool.getPoolSize(), pool.getQueue().size(),
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrSize(pool));
                    decrementCounter = 0;
                }
            } else {
                decrementCounter = 0;
            }

            if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
                if (++incrementCounter >= SAMPLING_TIMES) {
                    log.info("{} PoolSize={}+[{}] Threshold={}[{}-{}]% increment to {}", prefix,
                            pool.getPoolSize(), pool.getQueue().size(),
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
    static final boolean ENABLE_INHERIT_THREAD_LOCALS = SystemPropertyUtil.getBoolean(Constants.THREAD_POOL_ENABLE_INHERIT_THREAD_LOCALS, false);
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
        int poolSize = pool.getCorePoolSize() + getResizeQuantity();
        if (poolSize > 1000) {
            return 1000;
        }
        pool.setCorePoolSize(poolSize);
//        pool.execute(EMPTY);
        return poolSize;
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
    private final ConcurrentHashMap<Runnable, Task<?>> funcMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Tuple<ReentrantLock, AtomicInteger>> syncRoots = new ConcurrentHashMap<>(8);

    @Override
    public void setMaximumPoolSize(int maximumPoolSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        throw new UnsupportedOperationException();
    }

    public ThreadPool(String poolName) {
        //computeThreads(1, 2, 1)
        this(SystemPropertyUtil.getInt(Constants.THREAD_POOL_INIT_SIZE, CPU_THREADS + 1),
                SystemPropertyUtil.getInt(Constants.THREAD_POOL_QUEUE_CAPACITY, CPU_THREADS * 32), poolName);
    }

    public ThreadPool(int initThreads, int queueCapacity, String poolName) {
        this(initThreads, queueCapacity, DEFAULT_CPU_WATER_MARK, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param initThreads   最小线程数
     * @param queueCapacity LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int initThreads, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        super(Math.max(2, initThreads), Integer.MAX_VALUE,
                SystemPropertyUtil.getLong(Constants.THREAD_POOL_KEEP_ALIVE_SECONDS, 60 * 10), TimeUnit.SECONDS, new ThreadQueue<>(Math.max(1, queueCapacity)), newThreadFactory(poolName), (r, executor) -> {
                    if (r == EMPTY) {
                        return;
                    }
                    if (executor.isShutdown()) {
                        log.warn("ThreadPool {} is shutdown", poolName);
                        return;
                    }
                    executor.getQueue().offer(r);
                });
        super.allowCoreThreadTimeOut(true);
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

    public Future<?> run(Action task) {
        return run(task, null, null);
    }

    public Future<?> run(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return super.submit((Runnable) new Task<>(taskId, flags, task.toFunc()));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null, null);
    }

    public <T> Future<T> run(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return super.submit((Callable<T>) new Task<>(taskId, flags, task));
    }

    public CompletableFuture<Void> runAsync(Action task) {
        return runAsync(task, null, null);
    }

    public CompletableFuture<Void> runAsync(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return CompletableFuture.runAsync(new Task<>(taskId, flags, task.toFunc()), this);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return CompletableFuture.supplyAsync(new Task<>(taskId, flags, task), this);
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        Task<?> task = null;
        if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
            Object fn = Reflects.readField(r, "fn");
            task = as(fn, Task.class);
        } else if (r instanceof FutureTask) {
            Object fn = Reflects.readField(r, "callable");
            task = as(fn, Task.class);
            if (task == null) {
                fn = Reflects.readField(fn, "task");
            }
            task = as(fn, Task.class);
        }
        if (task != null) {
            funcMap.put(r, task);
            Object id = task.id;
            FlagsEnum<RunFlag> flags = task.flags;
            if (id != null) {
                if (flags.has(RunFlag.SINGLE)) {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(id);
                    if (!locker.left.tryLock()) {
                        throw new InterruptedException(String.format("SingleScope %s locked by other thread", id));
                    }
                    log.debug("{} {} tryLock", id, flags);
                } else if (flags.has(RunFlag.SYNCHRONIZED)) {
                    Tuple<ReentrantLock, AtomicInteger> locker = getLocker(id);
                    locker.right.incrementAndGet();
                    locker.left.lock();
                    log.debug("{} {} lock", id, flags);
                }
            }
            //TransmittableThreadLocal
            if (task.parent != null) {
                setThreadLocalMap(t, task.parent);
            }
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Task<?> task = getAs(r, true);
        if (task != null) {
            Object id = task.id;
            if (id != null) {
                Tuple<ReentrantLock, AtomicInteger> locker = syncRoots.get(id);
                if (locker != null) {
                    log.debug("{} {} unlock", id, task.flags);
                    locker.left.unlock();
                    if (locker.right.decrementAndGet() <= 0) {
                        syncRoots.remove(id);
                    }
                }
            }
            if (task.parent != null) {
                setThreadLocalMap(Thread.currentThread(), null);
            }
        }
    }

    void setThreadLocalMap(Thread t, InternalThreadLocalMap threadLocalMap) {
        if (t instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) t).setThreadLocalMap(threadLocalMap);
            return;
        }

        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = Reflects.readField(InternalThreadLocalMap.class, null, "slowThreadLocalMap");
        if (threadLocalMap == null) {
            slowThreadLocalMap.remove();
            return;
        }
        slowThreadLocalMap.set(threadLocalMap);
    }

    private Tuple<ReentrantLock, AtomicInteger> getLocker(Object id) {
        return syncRoots.computeIfAbsent(id, k -> Tuple.of(new ReentrantLock(), new AtomicInteger()));
    }

    private Task<?> getAs(Runnable command, boolean remove) {
        return remove ? funcMap.remove(command) : funcMap.get(command);
    }

    @Override
    public String toString() {
        return poolName;
//        return super.toString();
    }
}
