package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Subscribe;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Time;
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
            @Getter
            boolean shutdown;

            @Override
            public Future<?> submit(Runnable task) {
                return nextPool(task, null, null).submit(task);
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return nextPool(task, null, null).submit(task, result);
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return nextPool(task, null, null).submit(task);
            }

            @Override
            public void execute(Runnable command) {
                nextPool(command, null, null).execute(command);
            }

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return Collections.emptyList();
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }
        };
        completableFutureExecutor = Executors.unconfigurableExecutorService(executor);
        timer = new WheelTimer(executor);

        createPool(new ObjectChangedEvent(RxConfig.INSTANCE, Collections.emptyMap()));


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Action fn;
            while ((fn = shutdownActions.poll()) != null) {
                try {
                    fn.invoke();
                } catch (Throwable e) {
                    log.error("shutdownHook", e);
                }
            }
        }));

        // Delay CompletableFuture async-pool patching to avoid expanding the static-init dependency chain.
        timer.setTimeout(Tasks::initCompletableFutureAsyncPool, 1000);
        timer.setTimeout(() -> ObjectChangeTracker.DEFAULT.register(Tasks.class), 30000);
    }

    private static void initCompletableFutureAsyncPool() {
        if (!setCompletableFutureAsyncPool("asyncPool") && !setCompletableFutureAsyncPool("ASYNC_POOL")) {
            log.warn("setAsyncPool field not found");
        }
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

    @Subscribe(topicClass = RxConfig.class)
    static synchronized void createPool(ObjectChangedEvent event) {
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
                        nodes.remove(i);
                    }
                }
            }, 60000, nodes, TimeoutFlag.PERIOD.flags(TimeoutFlag.REPLACE));
        }
    }

    public static ThreadPool nextPool() {
        return nodes.get(ThreadLocalRandom.current().nextInt(0, poolCount));
    }

    static ThreadPool nextPool(Object task, Object taskId, FlagsEnum<RunFlag> flags) {
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
