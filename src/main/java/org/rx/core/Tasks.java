package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.bean.Tuple;
import org.rx.util.function.Action;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateAction;

import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

import static org.rx.core.App.quietly;
import static org.rx.core.Constants.NON_RAW_TYPES;
import static org.rx.core.Constants.NON_UNCHECKED;

//ExecutorCompletionService
//Java 11 and ForkJoinPool.commonPool() class loading issue
@Slf4j
public final class Tasks {
    private static final int POOL_COUNT = 2;
    //随机负载，如果methodA wait methodA，methodA在执行等待，methodB在threadPoolQueue，那么会出现假死现象。
    private static final List<ThreadPool> replicas;
    private static final ScheduledThreadPoolExecutor scheduler;
    private static final WheelTimer wheelTimer;
    private static final Queue<Action> shutdownActions = new ConcurrentLinkedQueue<>();

    static {
        replicas = new CopyOnWriteArrayList<>();
        for (int i = 0; i < POOL_COUNT; i++) {
            replicas.add(new ThreadPool(String.valueOf(i)));
        }
        scheduler = new ScheduledThreadPool();
        wheelTimer = new WheelTimer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Action fn;
            while ((fn = shutdownActions.poll()) != null) {
                try {
                    fn.invoke();
                } catch (Throwable e) {
                    log.warn("ShutdownHook", e);
                }
            }
        }));
    }

    public static ThreadPool pool() {
        return replicas.get(ThreadLocalRandom.current().nextInt(0, POOL_COUNT));
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
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
            log.warn("awaitNow {} timeout", Reflects.stackClass(2).getName());
        } catch (Exception e) {
            log.warn("awaitNow", e);
        }
        return null;
    }

    public static CompletableFuture<Void> run(Action task) {
        return pool().run(task);
    }

    public static CompletableFuture<Void> run(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().run(task, taskId, flags);
    }

    public static <T> CompletableFuture<T> run(Func<T> task) {
        return pool().run(task);
    }

    public static <T> CompletableFuture<T> run(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return pool().run(task, taskId, flags);
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static Tuple<CompletableFuture<Void>, CompletableFuture<Void>[]> anyOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }
        //Lambda method ref -> 对select的引用不明确
        CompletableFuture<Void>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of((CompletableFuture) CompletableFuture.anyOf(futures), futures);
    }

    @SuppressWarnings(NON_UNCHECKED)
    private static <T1, T2> Tuple<CompletableFuture<T1>, CompletableFuture<T2>[]> nullReturn() {
        return Tuple.of(CompletableFuture.completedFuture(null), new CompletableFuture[0]);
    }

    @SuppressWarnings(NON_UNCHECKED)
    public static <T> Tuple<CompletableFuture<T>, CompletableFuture<T>[]> anyOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<T>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public static Tuple<CompletableFuture<Void>, CompletableFuture<Void>[]> allOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<Void>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of(CompletableFuture.allOf(futures), futures);
    }

    public static <T> Tuple<CompletableFuture<Void>, CompletableFuture<T>[]> allOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<T>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of(CompletableFuture.allOf(futures), futures);
    }

    public static long getDelay(@NonNull Date time) {
        return time.getTime() - System.currentTimeMillis();
    }

    public static Future<Void> setTimeout(Action task, long delay) {
        return setTimeout(task, delay, null, null);
    }

    public static Future<Void> setTimeout(Action task, long delay, Object taskId, TimeoutFlag flag) {
        return setTimeout(() -> {
            task.invoke();
            return false;
        }, delay, taskId, flag);
    }

    public static Future<Void> setTimeout(PredicateAction task, long delay) {
        return wheelTimer.setTimeout(task, delay);
    }

    public static Future<Void> setTimeout(PredicateAction task, long delay, Object taskId, TimeoutFlag flag) {
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
        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay);
    }

    public static ScheduledFuture<?> schedule(Action task, long delay) {
        return schedule(task, delay, delay);
    }

    public static ScheduledFuture<?> schedule(@NonNull Action task, long initialDelay, long delay) {
        return scheduler.scheduleWithFixedDelay(wrap(task), initialDelay, delay, TimeUnit.MILLISECONDS);
    }

    static ThreadPool.Task<?> wrap(Action task) {
        //schedule 抛出异常会终止
        return new ThreadPool.Task<>(null, null, () -> quietly(task));
    }
}
