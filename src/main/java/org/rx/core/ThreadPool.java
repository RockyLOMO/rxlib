package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.management.OperatingSystemMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.InternalThreadLocalMap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.*;
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;

@SuppressWarnings(NON_UNCHECKED)
@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @RequiredArgsConstructor
    @Getter
    public static class MultiTaskFuture<T, TS> {
        static final MultiTaskFuture NULL = new MultiTaskFuture<>(CompletableFuture.completedFuture(null), new CompletableFuture[0]);
        final CompletableFuture<T> future;
        final CompletableFuture<TS>[] subFutures;
    }

    @RequiredArgsConstructor
    public static class ThreadQueue<T> extends LinkedTransferQueue<T> {
        private static final long serialVersionUID = 4283369202482437480L;
        final int queueCapacity;
        //todo cache len
        final AtomicInteger counter = new AtomicInteger();

        public boolean isFullLoad() {
            return counter.get() >= queueCapacity;
        }

        @Override
        public int size() {
            return counter.get();
        }

        @SneakyThrows
        @Override
        public boolean offer(T t) {
            if (isFullLoad()) {
                boolean logged = false;
                while (isFullLoad()) {
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
            int c = counter.decrementAndGet();
            synchronized (this) {
                if (c < 0) {
                    counter.set(super.size());
                    TraceHandler.INSTANCE.saveMetrics(Constants.THREAD_POOL_QUEUE, String.format("FIX SIZE %s -> %s", c, counter));
                }
                notify();
            }
        }
    }

    static class Task<T> implements Runnable, Callable<T>, Supplier<T> {
        final Func<T> fn;
        final FlagsEnum<RunFlag> flags;
        final Object id;
        final InternalThreadLocalMap parent;
        final String traceId;

        Task(Func<T> fn, FlagsEnum<RunFlag> flags, Object id) {
            if (flags == null) {
                flags = RunFlag.NONE.flags();
            }
            if (RxConfig.INSTANCE.threadPool.traceName != null) {
                flags.add(RunFlag.THREAD_TRACE);
            }

            this.fn = fn;
            this.flags = flags;
            this.id = id;
            parent = flags.has(RunFlag.INHERIT_FAST_THREAD_LOCALS) ? InternalThreadLocalMap.getIfSet() : null;
            traceId = CTX_TRACE_ID.get();
        }

        @SneakyThrows
        @Override
        public T call() {
            try {
                return fn.invoke();
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log(toString(), e);
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
            String hc = id != null ? id.toString() : Integer.toHexString(hashCode());
            return String.format("Task-%s[%s]", hc, flags.getValue());
        }
    }

    static class TaskContext {
        ReentrantLock lock;
        AtomicInteger lockRefCnt;
    }

    static class FutureTaskAdapter<T> extends FutureTask<T> {
        final Task<T> task;

        public FutureTaskAdapter(Callable<T> callable) {
            super(callable);
            task = as(callable, Task.class);
        }

        public FutureTaskAdapter(Runnable runnable, T result) {
            super(runnable, result);
            task = (Task<T>) runnable;
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
            log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% idle={} de/incrementCounter={}/{}", prefix,
                    pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrementCounter, incrementCounter);

            if (size > MIN_CORE_SIZE && (idle <= waterMark.getHigh() || cpuLoad.gt(waterMark.getHigh()))) {
                if (++decrementCounter >= SAMPLING_TIMES) {
                    log.info("{} Threshold={}[{}-{}]% idle={} decrement to {}", prefix,
                            cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrSize(pool));
                    decrementCounter = 0;
                }
            } else {
                decrementCounter = 0;
            }

            if (active >= size && cpuLoad.lt(waterMark.getLow())) {
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

        public void register(ThreadPoolExecutor pool, IntWaterMark cpuWaterMark) {
            if (cpuWaterMark == null) {
                return;
            }

            hold.put(pool, BiTuple.of(cpuWaterMark, 0, 0));
        }
    }

    //region static members
    public static volatile Func<String> traceIdGenerator;
    public static volatile BiAction<String> traceIdChangedHandler;
    static final ThreadLocal<String> CTX_TRACE_ID = new InheritableThreadLocal<>();
    static final String POOL_NAME_PREFIX = "℞Threads-";
    static final IntWaterMark DEFAULT_CPU_WATER_MARK = new IntWaterMark(RxConfig.INSTANCE.threadPool.lowCpuWaterMark,
            RxConfig.INSTANCE.threadPool.highCpuWaterMark);
    static final int MIN_CORE_SIZE = 2, MAX_CORE_SIZE = 1000;
    static final DynamicSizer SIZER = new DynamicSizer();
    static final FastThreadLocal<Boolean> ASYNC_CONTINUE = new FastThreadLocal<>();

    @SneakyThrows
    public static String startTrace(String traceId) {
        String tid = CTX_TRACE_ID.get();
        if (tid == null) {
            tid = traceId != null ? traceId :
                    traceIdGenerator != null ? traceIdGenerator.invoke() : SUID.randomSUID().toString();
            CTX_TRACE_ID.set(tid);
        } else if (traceId != null && !traceId.equals(tid)) {
            log.warn("The traceId already mapped to {} and can not set to {}", tid, traceId);
        }
//        log.info("trace init {}", tid);
        BiAction<String> fn = traceIdChangedHandler;
        if (fn != null) {
            fn.invoke(tid);
        }
        return tid;
    }

    public static String traceId() {
        return CTX_TRACE_ID.get();
    }

    @SneakyThrows
    public static void endTrace() {
//        log.info("trace remove");
        CTX_TRACE_ID.remove();
        BiAction<String> fn = traceIdChangedHandler;
        if (fn != null) {
            fn.invoke(null);
        }
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(Constants.CPU_THREADS, Math.floor(Constants.CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    static ThreadFactory newThreadFactory(String name) {
        //setUncaughtExceptionHandler跟全局ExceptionHandler.INSTANCE重复
        return new ThreadFactoryBuilder().setThreadFactory(FastThreadLocalThread::new)
                .setDaemon(true).setNameFormat(String.format("%s%s-%%d", POOL_NAME_PREFIX, name)).build();
    }

    static int incrSize(ThreadPoolExecutor pool) {
        int poolSize = pool.getCorePoolSize() + RxConfig.INSTANCE.threadPool.resizeQuantity;
        if (poolSize > MAX_CORE_SIZE) {
            return MAX_CORE_SIZE;
        }
        pool.setCorePoolSize(poolSize);
        return poolSize;
    }

    static int decrSize(ThreadPoolExecutor pool) {
        int poolSize = Math.max(MIN_CORE_SIZE, pool.getCorePoolSize() - RxConfig.INSTANCE.threadPool.resizeQuantity);
        pool.setCorePoolSize(poolSize);
        return poolSize;
    }

    static boolean asyncContinueFlag(boolean def) {
        Boolean ac = ASYNC_CONTINUE.getIfExists();
        ASYNC_CONTINUE.remove();
        if (ac == null) {
            return def;
        }
        return ac;
    }
    //endregion

    //region instance members
    @Getter
    final String poolName;
    final Map<Runnable, Task<?>> taskMap = new ConcurrentHashMap<>();
    final Map<Object, TaskContext> taskCtxMap = new ConcurrentHashMap<>(8);

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
        this(RxConfig.INSTANCE.threadPool.initSize, RxConfig.INSTANCE.threadPool.queueCapacity, poolName);
    }

    public ThreadPool(int initSize, int queueCapacity, String poolName) {
        this(initSize, queueCapacity, DEFAULT_CPU_WATER_MARK, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param initSize      最小线程数
     * @param queueCapacity LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int initSize, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        super(checkSize(initSize), Integer.MAX_VALUE,
                RxConfig.INSTANCE.threadPool.keepAliveSeconds, TimeUnit.SECONDS, new ThreadQueue<>(checkCapacity(queueCapacity)), newThreadFactory(poolName), (r, executor) -> {
                    if (executor.isShutdown()) {
                        log.warn("ThreadPool {} is shutdown", poolName);
                        return;
                    }
                    executor.getQueue().offer(r);
                });
        super.allowCoreThreadTimeOut(true);
        this.poolName = poolName;

        setDynamicSize(cpuWaterMark);
    }

    private static int checkSize(int size) {
        if (size <= 0) {
            size = Constants.CPU_THREADS + 1;
        }
        return size;
    }

    private static int checkCapacity(int capacity) {
        if (capacity <= 0) {
            capacity = Constants.CPU_THREADS * 32;
        }
        return capacity;
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
    //endregion

    //region v1
    public Future<Void> run(Action task) {
        return run(task, null, null);
    }

    public Future<Void> run(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return (Future<Void>) super.submit((Runnable) new Task<>(task.toFunc(), flags, taskId));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null, null);
    }

    public <T> Future<T> run(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return super.submit((Callable<T>) new Task<>(task, flags, taskId));
    }

    //ExecutorCompletionService
    @SneakyThrows
    public <T> T runAny(@NonNull Collection<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) () -> {
            try {
                return p.invoke();
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }).toList();
        return timeoutMillis > 0 ? super.invokeAny(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAny(callables);
    }

    @SneakyThrows
    public <T> List<Future<T>> runAll(@NonNull Collection<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) () -> {
            try {
                return p.invoke();
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }).toList();
        return timeoutMillis > 0 ? super.invokeAll(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAll(callables);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTaskAdapter<>(runnable, value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTaskAdapter<>(callable);
    }
    //endregion

    public CompletableFuture<Void> runAsync(Action task) {
        return runAsync(task, null, null);
    }

    public CompletableFuture<Void> runAsync(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return CompletableFuture.runAsync(new Task<>(task.toFunc(), flags, taskId), this);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return CompletableFuture.supplyAsync(new Task<>(task, flags, taskId), this);
    }

    public <T> MultiTaskFuture<T, T> runAnyAsync(Collection<Func<T>> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return MultiTaskFuture.NULL;
        }

        CompletableFuture<T>[] futures = Linq.from(tasks).select(this::runAsync).toArray();
        return new MultiTaskFuture<>((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public <T> MultiTaskFuture<Void, T> runAllAsync(Collection<Func<T>> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return MultiTaskFuture.NULL;
        }

        CompletableFuture<T>[] futures = Linq.from(tasks).select(this::runAsync).toArray();
        return new MultiTaskFuture<>(CompletableFuture.allOf(futures), futures);
    }

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        Task<?> task = null;
        if (r instanceof FutureTaskAdapter) {
            task = ((FutureTaskAdapter<?>) r).task;
        } else if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
            task = as(Reflects.readField(r, "fn"), Task.class);
        }
        if (task == null) {
            return;
        }

        taskMap.put(r, task);
        FlagsEnum<RunFlag> flags = task.flags;
        if (flags.has(RunFlag.SINGLE)) {
            TaskContext ctx = getContextForLock(task.id);
            if (!ctx.lock.tryLock()) {
                throw new InterruptedException(String.format("SingleScope %s locked by other thread", task.id));
            }
            ctx.lockRefCnt.incrementAndGet();
            log.debug("CTX tryLock {} -> {}", task.id, flags.name());
        } else if (flags.has(RunFlag.SYNCHRONIZED)) {
            TaskContext ctx = getContextForLock(task.id);
            ctx.lockRefCnt.incrementAndGet();
            ctx.lock.lock();
            log.debug("CTX lock {} -> {}", task.id, flags.name());
        }
        if (flags.has(RunFlag.PRIORITY) && !getQueue().isEmpty()) {
            incrSize(this);
        }
        //TransmittableThreadLocal
        if (task.parent != null) {
            setThreadLocalMap(t, task.parent);
        }
        if (flags.has(RunFlag.THREAD_TRACE)) {
            startTrace(task.traceId);
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Task<?> task = getTask(r, true);
        if (task == null) {
            return;
        }

        FlagsEnum<RunFlag> flags = task.flags;
        Object id = task.id;
        if (id != null) {
            TaskContext ctx = taskCtxMap.get(id);
            if (ctx != null) {
                boolean doRemove = false;
                if (ctx.lockRefCnt.decrementAndGet() <= 0) {
                    taskCtxMap.remove(id);
                    doRemove = true;
                }
                log.debug("CTX unlock{} {} -> {}", doRemove ? " & clear" : "", id, task.flags.name());
                ctx.lock.unlock();
            }
        }
        if (task.parent != null) {
            setThreadLocalMap(Thread.currentThread(), null);
        }
        if (flags.has(RunFlag.THREAD_TRACE)) {
            endTrace();
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

    private TaskContext getContextForLock(Object id) {
        if (id == null) {
            throw new InvalidException("SINGLE or SYNCHRONIZED flag require a taskId");
        }

        return taskCtxMap.computeIfAbsent(id, k -> {
            TaskContext ctx = new TaskContext();
            ctx.lock = new ReentrantLock();
            ctx.lockRefCnt = new AtomicInteger();
            return ctx;
        });
    }

    private Task<?> getTask(Runnable command, boolean remove) {
        return remove ? taskMap.remove(command) : taskMap.get(command);
    }

    @Override
    public String toString() {
        return poolName;
//        return super.toString();
    }
}
