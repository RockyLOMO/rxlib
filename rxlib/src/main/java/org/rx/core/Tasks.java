package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Subscribe;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

import static org.rx.core.Extends.circuitContinue;
import static org.rx.core.RxConfig.ConfigNames.THREAD_POOL_REPLICAS;

//Java 11 ForkJoinPool.commonPool() has class loading issue
@Slf4j
public final class Tasks {
    //Random load balance, if methodA wait methodA, methodA is executing wait and methodB is in ThreadPoolQueue, then there will be a false death.
    static final List<ThreadPool> nodes = new CopyOnWriteArrayList<>();
    static final ExecutorService executor;
    static final ExecutorService completableFutureExecutor;
    static final WheelTimer timer;
    static final Queue<Action> shutdownActions = new ConcurrentLinkedQueue<>();
    static final Object unsafe;
    static final Method unsafeStaticFieldBase;
    static final Method unsafeStaticFieldOffset;
    static final Method unsafePutObject;
    static int poolCount;

    static {
        Object u = null;
        Method staticFieldBase = null, staticFieldOffset = null, putObject = null;
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            u = field.get(null);
            Class<?> unsafeType = u.getClass();
            staticFieldBase = unsafeType.getMethod("staticFieldBase", Field.class);
            staticFieldOffset = unsafeType.getMethod("staticFieldOffset", Field.class);
            putObject = unsafeType.getMethod("putObject", Object.class, long.class, Object.class);
        } catch (Throwable e) {
            log.warn("initUnsafe", e);
        }
        unsafe = u;
        unsafeStaticFieldBase = staticFieldBase;
        unsafeStaticFieldOffset = staticFieldOffset;
        unsafePutObject = putObject;
        executor = new AbstractExecutorService() {
            volatile boolean shutdown;
            volatile boolean shutdownNow;

            private void ensureRunning() {
                if (shutdown) {
                    throw new RejectedExecutionException("Tasks executor is shutdown");
                }
            }

            @Override
            public Future<?> submit(Runnable task) {
                ensureRunning();
                return nextPool(task, null, null).submit(task);
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                ensureRunning();
                return nextPool(task, null, null).submit(task, result);
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                ensureRunning();
                return nextPool(task, null, null).submit(task);
            }

            @Override
            public void execute(Runnable command) {
                ensureRunning();
                nextPool(command, null, null).execute(command);
            }

            @Override
            public synchronized void shutdown() {
                if (shutdown) {
                    return;
                }
                shutdown = true;
                timer.shutdown();
                for (ThreadPool node : nodes) {
                    CpuWatchman.INSTANCE.unregister(node);
                    node.shutdown();
                }
                drainShutdownActions();
            }

            @Override
            public synchronized List<Runnable> shutdownNow() {
                if (shutdownNow) {
                    return Collections.emptyList();
                }
                shutdown = true;
                shutdownNow = true;
                List<Runnable> pending = new ArrayList<>();
                pending.addAll(timer.shutdownNow());
                for (ThreadPool node : nodes) {
                    CpuWatchman.INSTANCE.unregister(node);
                    pending.addAll(node.shutdownNow());
                }
                CpuWatchman.INSTANCE.shutdown();
                drainShutdownActions();
                return pending;
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                if (!shutdown) {
                    return false;
                }
                if (!timer.isTerminated()) {
                    return false;
                }
                for (ThreadPool node : nodes) {
                    if (!node.isTerminated()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            @SneakyThrows
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                long deadline = System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(timeout, unit);
                while (!isTerminated() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())));
                }
                return isTerminated();
            }
        };
        completableFutureExecutor = Executors.unconfigurableExecutorService(executor);
        timer = new WheelTimer(executor);

        createPool(new ObjectChangedEvent(RxConfig.INSTANCE, Collections.emptyMap()));


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            drainShutdownActions();
        }));

        // Delay CompletableFuture async-pool patching to avoid expanding the static-init dependency chain.
        timer.setTimeout(() -> initCompletableFutureAsyncPool(), 1000);
        timer.setTimeout(() -> ObjectChangeTracker.DEFAULT.register(Tasks.class), 30000);
    }

    static boolean initCompletableFutureAsyncPool() {
        if (!RxConfig.INSTANCE.threadPool.patchCompletableFutureAsyncPool) {
            log.info("CompletableFuture async pool patch disabled by app.threadPool.patchCompletableFutureAsyncPool=false");
            recordCompletableFuturePatchMetric("disabled", "none");
            return false;
        }

        if (setCompletableFutureAsyncPool("asyncPool")) {
            log.info("CompletableFuture async pool patched, field=asyncPool");
            recordCompletableFuturePatchMetric("success", "asyncPool");
            return true;
        }
        if (setCompletableFutureAsyncPool("ASYNC_POOL")) {
            log.info("CompletableFuture async pool patched, field=ASYNC_POOL");
            recordCompletableFuturePatchMetric("success", "ASYNC_POOL");
            return true;
        }
        log.warn("CompletableFuture async pool patch failed, field not found or write rejected, java={}",
                System.getProperty("java.version"));
        recordCompletableFuturePatchMetric("failed", "none");
        return false;
    }

    private static boolean setCompletableFutureAsyncPool(String fieldName) {
        try {
            Field field = CompletableFuture.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            // Force class initialization first, otherwise JDK17 may overwrite the patched field later.
            field.get(null);
            if (!Modifier.isFinal(field.getModifiers())) {
                field.set(null, completableFutureExecutor);
            } else if (!unsafeWriteStaticField(field, completableFutureExecutor)) {
                return false;
            }
            return field.get(null) == completableFutureExecutor;
        } catch (NoSuchFieldException e) {
            return false;
        } catch (Throwable e) {
            log.warn("setAsyncPool {}", fieldName, e);
            return false;
        }
    }

    private static boolean unsafeWriteStaticField(Field field, Object value) {
        if (unsafe == null || unsafeStaticFieldBase == null || unsafeStaticFieldOffset == null || unsafePutObject == null) {
            return false;
        }
        try {
            Object base = unsafeStaticFieldBase.invoke(unsafe, field);
            long offset = ((Number) unsafeStaticFieldOffset.invoke(unsafe, field)).longValue();
            unsafePutObject.invoke(unsafe, base, offset, value);
            return field.get(null) == value;
        } catch (Throwable e) {
            log.warn("unsafeWriteStaticField {}", field.getName(), e);
            return false;
        }
    }

    private static void recordCompletableFuturePatchMetric(String result, String field) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("rx.thread_pool.completable_future.patch.count", 1D,
                    "result=" + result + ",field=" + field);
        }
    }

    private static void drainShutdownActions() {
        Action fn;
        while ((fn = shutdownActions.poll()) != null) {
            try {
                fn.invoke();
            } catch (Throwable e) {
                log.error("shutdownHook", e);
            }
        }
    }

    @Subscribe(topicClass = RxConfig.class)
    static synchronized void createPool(ObjectChangedEvent event) {
        if (executor.isShutdown()) {
            log.warn("Skip create ThreadPool nodes because Tasks executor is shutdown");
            return;
        }
        int newCount = RxConfig.INSTANCE.threadPool.replicas;
        if (newCount == poolCount) {
            return;
        }

        log.info("RxMeta {} changed {} -> {}", THREAD_POOL_REPLICAS, poolCount, newCount);
        for (int i = 0; i < newCount; i++) {
            nodes.add(0, new ThreadPool(String.format("N%s", i)));
        }
        poolCount = newCount;

        if (nodes.size() > poolCount) {
            timer.setTimeout(() -> {
                if (nodes.size() == poolCount) {
                    circuitContinue(false);
                    return;
                }
                for (int i = poolCount; i < nodes.size(); i++) {
                    if (nodes.get(i).getActiveCount() == 0) {
                        ThreadPool removed = nodes.remove(i);
                        CpuWatchman.INSTANCE.unregister(removed);
                    }
                }
            }, 60000, nodes, TimeoutFlag.PERIOD.flags(TimeoutFlag.REPLACE));
        }
    }

    public static ThreadPool nextPool() {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("Tasks executor is shutdown");
        }
        return nodes.get(ThreadLocalRandom.current().nextInt(0, poolCount));
    }

    static ThreadPool nextPool(Object task, Object taskId, FlagsEnum<RunFlag> flags) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("Tasks executor is shutdown");
        }
        ThreadPool.Task<?> adapted = ThreadPool.Task.as(task);
        if (adapted != null) {
            if (taskId == null) {
                taskId = adapted.id;
            }
            if (flags == null) {
                flags = adapted.flags;
            }
        }
        if (taskId != null && flags != null && flags.has(RunFlag.SINGLE)) {
            return nodes.get(Math.floorMod(taskId.hashCode(), poolCount));
        }
        return nextPool();
    }

    public static ExecutorService executor() {
        return executor;
    }

    public static WheelTimer timer() {
        return timer;
    }

    public static void shutdown() {
        executor.shutdown();
    }

    public static List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public static void addShutdownHook(Action fn) {
        shutdownActions.offer(fn);
    }

    @SneakyThrows
    @SafeVarargs
    public static <T> T sequentialRetry(Func<T>... funcs) {
        Throwable last = null;
        for (Func<T> func : funcs) {
            try {
                return func.invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    @SneakyThrows
    @SafeVarargs
    public static <T> T randomRetry(Func<T>... funcs) {
        int mid = ThreadLocalRandom.current().nextInt(0, funcs.length);
        Throwable last = null;
        for (int i = 0; i < mid; i++) {
            try {
                return funcs[i].invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        for (int i = mid; i < funcs.length; i++) {
            try {
                return funcs[i].invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    @SneakyThrows
    public static <T> T await(Future<T> future) {
        if (future instanceof CompletableFuture) {
            return ((CompletableFuture<T>) future).join();
        }
        return future.get();
    }

    public static <T> T awaitQuietly(Func<T> func, long millis) {
        return awaitQuietly(run(func), millis);
    }

    public static <T> T awaitQuietly(Future<T> future, long millis) {
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            //catch +1 ?
            log.warn("awaitNow {} timeout", Reflects.CLASS_TRACER.getClassTrace(2).getName());
        } catch (Exception e) {
            log.error("awaitQuietly", e);
        }
        return null;
    }

    public static Future<Void> run(Action task) {
        return nextPool(task, null, null).run(task);
    }

    public static Future<Void> run(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return nextPool(task, taskId, flags).run(task, taskId, flags);
    }

    public static <T> Future<T> run(Func<T> task) {
        return nextPool(task, null, null).run(task);
    }

    public static <T> Future<T> run(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return nextPool(task, taskId, flags).run(task, taskId, flags);
    }

    public static CompletableFuture<Void> runAsync(Action task) {
        return nextPool(task, null, null).runAsync(task);
    }

    public static CompletableFuture<Void> runAsync(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return nextPool(task, taskId, flags).runAsync(task, taskId, flags);
    }

    public static <T> CompletableFuture<T> runAsync(Func<T> task) {
        return nextPool(task, null, null).runAsync(task);
    }

    public static <T> CompletableFuture<T> runAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return nextPool(task, taskId, flags).runAsync(task, taskId, flags);
    }

    public static TimeoutFuture<?> setTimeout(Action task, long delay) {
        return timer.setTimeout(task, delay);
    }

    public static TimeoutFuture<?> setTimeout(Action task, long delay, Object taskId, FlagsEnum<TimeoutFlag> flags) {
        return timer.setTimeout(task, delay, taskId, flags);
    }

    public static List<? extends ScheduledFuture<?>> scheduleDaily(Action task, String... timeArray) {
        return Linq.from(timeArray).select(p -> scheduleDaily(task, Time.valueOf(p))).toList();
    }

    /**
     * 每天按指定时间执行
     *
     * @param task Action
     * @param time "HH:mm:ss"
     * @return Future
     */
    public static ScheduledFuture<?> scheduleDaily(@NonNull Action task, @NonNull Time time) {
        long oneDay = Constants.ONE_DAY_TOTAL_SECONDS * 1000;
        long initDelay = DateTime.now().setTimePart(time.toString()).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedulePeriod(task, initDelay, oneDay);
    }

    public static ScheduledFuture<?> schedulePeriod(Action task, long period) {
        return schedulePeriod(task, period, period);
    }

    public static ScheduledFuture<?> schedulePeriod(@NonNull Action task, long initialDelay, long period) {
        return timer.setTimeout(task, d -> d == 0 ? initialDelay : period, null, Constants.TIMER_PERIOD_FLAG);
    }
}
