package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.*;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.exception.ExceptionHandler;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.sql.Time;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

//Java 11 and ForkJoinPool.commonPool() class loading issue
public final class Tasks {
    private static final int POOL_COUNT = RxConfig.INSTANCE.threadPool.replicas;
    //随机负载，如果methodA wait methodA，methodA在执行等待，methodB在threadPoolQueue，那么会出现假死现象。
    private static final List<ThreadPool> replicas = new CopyOnWriteArrayList<>();
    private static final WheelTimer wheelTimer;
    private static final Queue<Action> shutdownActions = new ConcurrentLinkedQueue<>();

    static {
        for (int i = 0; i < POOL_COUNT; i++) {
            replicas.add(new ThreadPool(String.valueOf(i)));
        }
        wheelTimer = new WheelTimer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Action fn;
            while ((fn = shutdownActions.poll()) != null) {
                try {
                    fn.invoke();
                } catch (Throwable e) {
                    ExceptionHandler.INSTANCE.log(e);
                }
            }
        }));
    }

    public static ThreadPool pool() {
        return replicas.get(ThreadLocalRandom.current().nextInt(0, POOL_COUNT));
    }

    public static WheelTimer timer() {
        return wheelTimer;
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
            ExceptionHandler.INSTANCE.log("awaitNow {} timeout", Reflects.stackClass(2).getName());
        } catch (Exception e) {
            ExceptionHandler.INSTANCE.log(e);
        }
        return null;
    }

    public static Future<Void> run(Action task) {
        return pool().run(task);
    }

    public static Future<Void> run(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().run(task, taskId, flags);
    }

    public static <T> Future<T> run(Func<T> task) {
        return pool().run(task);
    }

    public static <T> Future<T> run(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().run(task, taskId, flags);
    }

    public static CompletableFuture<Void> runAsync(Action task) {
        return pool().runAsync(task);
    }

    public static CompletableFuture<Void> runAsync(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().runAsync(task, taskId, flags);
    }

    public static <T> CompletableFuture<T> runAsync(Func<T> task) {
        return pool().runAsync(task);
    }

    public static <T> CompletableFuture<T> runAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().runAsync(task, taskId, flags);
    }

    public static TimeoutFuture<?> setTimeout(Action task, long delay) {
        return wheelTimer.setTimeout(task, delay);
    }

    public static TimeoutFuture<?> setTimeout(Action task, long delay, Object taskId, TimeoutFlag flag) {
        return wheelTimer.setTimeout(task, delay, taskId, flag);
    }

    public static List<? extends ScheduledFuture<?>> scheduleDaily(Action task, String... timeArray) {
        return NQuery.of(timeArray).select(p -> scheduleDaily(task, Time.valueOf(p))).toList();
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
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedulePeriod(task, initDelay, oneDay);
    }

    public static ScheduledFuture<?> schedulePeriod(Action task, long period) {
        return schedulePeriod(task, period, period);
    }

    public static ScheduledFuture<?> schedulePeriod(@NonNull Action task, long initialDelay, long period) {
        return wheelTimer.setTimeout(task, d -> d == 0 ? initialDelay : period, null, TimeoutFlag.PERIOD);
    }
}
