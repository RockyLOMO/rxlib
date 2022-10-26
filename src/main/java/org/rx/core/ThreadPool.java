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
import org.rx.bean.*;
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;

@SuppressWarnings(NON_UNCHECKED)
@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @RequiredArgsConstructor
    @Getter
    public static class MultiTaskFuture<T, TS> {
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

    @RequiredArgsConstructor
    static class CompletableFutureWrapper<T> extends CompletableFuture<T> {
        final Executor pool;
        final String traceId;
        final boolean reuseOnUni;
        CompletableFuture<T> delegate;

        <R> CompletableFutureWrapper<R> uniStage(CompletableFuture<R> delegate) {
            CompletableFutureWrapper<R> wrapper = reuseOnUni
                    ? (CompletableFutureWrapper<R>) this
                    : new CompletableFutureWrapper<>(pool, traceId, false);
            wrapper.delegate = delegate;
            return wrapper;
        }

        Executor uniPool(Executor executor) {
//            return ForkJoinPool.commonPool();
            return executor != null ? executor : pool;
        }

        @Override
        public boolean equals(Object o) {
            return delegate.equals(o);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        <X, R> Function<X, R> wrap(Function<X, R> fn) {
            return t -> {
                ThreadPool.startTrace(traceId);
                try {
                    return fn.apply(t);
                } finally {
                    ThreadPool.endTrace();
                }
            };
        }

        <X> Consumer<X> wrap(Consumer<X> fn) {
            return t -> {
                ThreadPool.startTrace(traceId);
                try {
                    fn.accept(t);
                } finally {
                    ThreadPool.endTrace();
                }
            };
        }

        Runnable wrap(Runnable fn) {
            return () -> {
                ThreadPool.startTrace(traceId);
                try {
                    fn.run();
                } finally {
                    ThreadPool.endTrace();
                }
            };
        }

        <X, Y, R> BiFunction<X, Y, R> wrap(BiFunction<X, Y, R> fn) {
            return (t, u) -> {
                ThreadPool.startTrace(traceId);
                try {
                    return fn.apply(t, u);
                } finally {
                    ThreadPool.endTrace();
                }
            };
        }

        <X, Y> BiConsumer<X, Y> wrap(BiConsumer<X, Y> fn) {
            return (t, u) -> {
                ThreadPool.startTrace(traceId);
                try {
                    fn.accept(t, u);
                } finally {
                    ThreadPool.endTrace();
                }
            };
        }

        @Override
        public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
            return uniStage(delegate.thenApply(wrap(fn)));
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
            return thenApplyAsync(fn, null);
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
            return uniStage(delegate.thenApplyAsync(wrap(fn), uniPool(executor)));
        }

        @Override
        public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
            return uniStage(delegate.thenAccept(wrap(action)));
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
            return thenAcceptAsync(action, null);
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
            return uniStage(delegate.thenAcceptAsync(wrap(action), uniPool(executor)));
        }

        @Override
        public CompletableFuture<Void> thenRun(Runnable action) {
            return uniStage(delegate.thenRun(wrap(action)));
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable action) {
            return thenRunAsync(action, null);
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
            return uniStage(delegate.thenRunAsync(wrap(action), uniPool(executor)));
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
            return uniStage(delegate.thenCombine(other, wrap(fn)));
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
            return thenCombineAsync(other, fn, null);
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
            return uniStage(delegate.thenCombineAsync(other, wrap(fn), uniPool(executor)));
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
            return uniStage(delegate.thenAcceptBoth(other, wrap(action)));
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
            return thenAcceptBothAsync(other, action, null);
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
            return uniStage(delegate.thenAcceptBothAsync(other, wrap(action), uniPool(executor)));
        }

        @Override
        public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
            return uniStage(delegate.runAfterBoth(other, wrap(action)));
        }

        @Override
        public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
            return runAfterBothAsync(other, action, null);
        }

        @Override
        public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
            return uniStage(delegate.runAfterBothAsync(other, wrap(action), uniPool(executor)));
        }

        @Override
        public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
            return uniStage(delegate.applyToEither(other, wrap(fn)));
        }

        @Override
        public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
            return applyToEitherAsync(other, fn, null);
        }

        @Override
        public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
            return uniStage(delegate.applyToEitherAsync(other, wrap(fn), uniPool(executor)));
        }

        @Override
        public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
            return uniStage(delegate.acceptEither(other, wrap(action)));
        }

        @Override
        public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
            return acceptEitherAsync(other, action, null);
        }

        @Override
        public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
            return uniStage(delegate.acceptEitherAsync(other, wrap(action), uniPool(executor)));
        }

        @Override
        public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
            return uniStage(delegate.runAfterEither(other, wrap(action)));
        }

        @Override
        public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
            return runAfterEitherAsync(other, action, null);
        }

        @Override
        public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
            return uniStage(delegate.runAfterEitherAsync(other, wrap(action), uniPool(executor)));
        }

        @Override
        public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
            return uniStage(delegate.thenCompose(wrap(fn)));
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
            return thenComposeAsync(fn, null);
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
            return uniStage(delegate.thenComposeAsync(wrap(fn), uniPool(executor)));
        }

        @Override
        public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            return uniStage(delegate.whenComplete(wrap(action)));
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
            return whenCompleteAsync(action, null);
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
            return uniStage(delegate.whenCompleteAsync(wrap(action), uniPool(executor)));
        }

        @Override
        public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
            return uniStage(delegate.handle(wrap(fn)));
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
            return handleAsync(fn, null);
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
            return uniStage(delegate.handleAsync(wrap(fn), uniPool(executor)));
        }

        @Override
        public CompletableFuture<T> toCompletableFuture() {
            return uniStage(delegate.toCompletableFuture());
        }

        @Override
        public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
            return uniStage(delegate.exceptionally(wrap(fn)));
        }

        //region ignore
        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }

        @Override
        public T join() {
            return delegate.join();
        }

        @Override
        public T getNow(T valueIfAbsent) {
            return delegate.getNow(valueIfAbsent);
        }

        @Override
        public boolean complete(T value) {
            return delegate.complete(value);
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            return delegate.completeExceptionally(ex);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isCompletedExceptionally() {
            return delegate.isCompletedExceptionally();
        }

        @Override
        public void obtrudeValue(T value) {
            delegate.obtrudeValue(value);
        }

        @Override
        public void obtrudeException(Throwable ex) {
            delegate.obtrudeException(ex);
        }

        @Override
        public int getNumberOfDependents() {
            return delegate.getNumberOfDependents();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
        //endregion
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
                    traceIdGenerator != null ? traceIdGenerator.invoke() : ULID.randomULID().toBase64String();
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
    final Map<Object, CompletableFuture<?>> taskSerialMap = new ConcurrentHashMap<>(8);

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

    @SneakyThrows
    public <T> T runAny(Collection<Func<T>> tasks, long timeoutMillis) {
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
    public <T> List<Future<T>> runAll(Collection<Func<T>> tasks, long timeoutMillis) {
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

    public <T> CompletionService<T> newCompletionService() {
        return new ExecutorCompletionService<>(this);
    }
    //endregion

    //region v2
    public CompletableFuture<Void> runAsync(Action task) {
        return runAsync(task, null, null);
    }

    public CompletableFuture<Void> runAsync(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<Void> t = new Task<>(task.toFunc(), flags, taskId);
        return wrap(CompletableFuture.runAsync(t, this), t.traceId);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<T> t = new Task<>(task, flags, taskId);
        return wrap(CompletableFuture.supplyAsync(t, this), t.traceId);
    }

    public <T> Future<T> runSerialAsync(Func<T> task, Object taskId) {
        return runSerialAsync(task, taskId, null);
    }

    public <T> Future<T> runSerialAsync(@NonNull Func<T> task, @NonNull Object taskId, FlagsEnum<RunFlag> flags) {
        AtomicBoolean init = new AtomicBoolean();
        CompletableFuture<T> f = (CompletableFuture<T>) taskSerialMap.computeIfAbsent(taskId, k -> {
            init.set(true);
            Task<T> t = new Task<>(task, flags, taskId);
            return wrap(CompletableFuture.supplyAsync(t, this), t.traceId, true)
                    .whenCompleteAsync((r, e) -> taskSerialMap.remove(taskId));
        });
        if (!init.get()) {
            f = f.thenApplyAsync(t -> {
                try {
                    return task.invoke();
                } catch (Throwable e) {
                    throw InvalidException.sneaky(e);
                }
            }, this);
        }
        return f;
    }

    public <T> MultiTaskFuture<T, T> runAnyAsync(Collection<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = new Task<>(task, null, null);
            return CompletableFuture.supplyAsync(t, this);
        }).toArray();
        return new MultiTaskFuture<>(wrap((CompletableFuture<T>) CompletableFuture.anyOf(futures), CTX_TRACE_ID.get()), futures);
    }

    public <T> MultiTaskFuture<Void, T> runAllAsync(Collection<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = new Task<>(task, null, null);
            //allOfFuture.join()会hang住
//            return wrap(CompletableFuture.supplyAsync(t, this), t.traceId);
            return CompletableFuture.supplyAsync(t, this);
        }).toArray();
        return new MultiTaskFuture<>(wrap(CompletableFuture.allOf(futures), CTX_TRACE_ID.get()), futures);
    }

    private <T> CompletableFutureWrapper<T> wrap(CompletableFuture<T> delegate, String traceId) {
        return wrap(delegate, traceId, false);
    }

    private <T> CompletableFutureWrapper<T> wrap(CompletableFuture<T> delegate, String traceId, boolean reuseOnUni) {
        CompletableFutureWrapper<T> wrapper = new CompletableFutureWrapper<>(this, traceId, reuseOnUni);
        wrapper.delegate = delegate;
        return wrapper;
    }
    //endregion

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

    private void setThreadLocalMap(Thread t, InternalThreadLocalMap threadLocalMap) {
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
