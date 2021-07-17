package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.util.function.Action;
import org.rx.util.function.Func;
import org.slf4j.helpers.MessageFormatter;

import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

//ExecutorCompletionService
//Java 11 and ForkJoinPool.commonPool() class loading issue
@Slf4j
public final class Tasks {
    @RequiredArgsConstructor
    @Getter
    public static class UncaughtExceptionContext {
        private final String format;
        private final Object[] args;
        @Setter
        private boolean raised;
    }

    private static final int POOL_COUNT = 2;
    //随机负载，如果methodA wait methodA，methodA在执行等待，methodB在threadPoolQueue，那么会出现假死现象。
    private static final List<TaskScheduler> replicas;
    //HashedWheelTimer
    private static final ScheduledExecutorService scheduler;
    private static final FastThreadLocal<UncaughtExceptionContext> raiseFlag = new FastThreadLocal<>();

    static {
        int coreSize = Math.max(1, ThreadPool.CPU_THREADS / POOL_COUNT);
        replicas = new CopyOnWriteArrayList<>();
        for (int i = 0; i < POOL_COUNT; i++) {
            replicas.add(new TaskScheduler(coreSize, String.valueOf(i)));
        }
        scheduler = new ScheduledThreadPoolExecutor(2, replicas.get(0).getThreadFactory());
    }

    public static TaskScheduler pool() {
        return replicas.get(ThreadLocalRandom.current().nextInt(0, POOL_COUNT));
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public static UncaughtExceptionContext raisingContext() {
        return raiseFlag.getIfExists();
    }

    public static boolean raiseUncaughtException(String format, Object... args) {
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        if (e == null) {
            log.warn("ThrowableCandidate is null");
            return false;
        }
        UncaughtExceptionContext context = isNull(raisingContext(), new UncaughtExceptionContext(format, args));
        if (context.isRaised()) {
            return true;
        }
        context.setRaised(true);
        raiseFlag.set(context);
        try {
            Thread.UncaughtExceptionHandler handler = isNull(Thread.getDefaultUncaughtExceptionHandler(), (p, x) -> log.error(context.getFormat(), context.getArgs()));
            handler.uncaughtException(Thread.currentThread(), e);
        } catch (Throwable ie) {
            log.error("UncaughtException", ie);
        } finally {
            raiseFlag.remove();
        }
        return true;
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
        } catch (Exception e) {
            log.warn("awaitNow", e);
        }
        return null;
    }

    public static CompletableFuture<Void> run(Action task) {
        return pool().run(task);
    }

    public static CompletableFuture<Void> run(Action task, String taskName, RunFlag runFlag) {
        return pool().run(task, taskName, runFlag);
    }

    public static <T> CompletableFuture<T> run(Func<T> task) {
        return pool().run(task);
    }

    public static <T> CompletableFuture<T> run(Func<T> task, String taskName, RunFlag runFlag) {
        return pool().run(task, taskName, runFlag);
    }

    public static Tuple<CompletableFuture<Void>, CompletableFuture<Void>[]> anyOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }
        //Lambda method ref -> 对select的引用不明确
        CompletableFuture<Void>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of((CompletableFuture) CompletableFuture.anyOf(futures), futures);
    }

    private static <T1, T2> Tuple<CompletableFuture<T1>, CompletableFuture<T2>[]> nullReturn() {
        return Tuple.of(CompletableFuture.completedFuture(null), new CompletableFuture[0]);
    }

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

    public static List<? extends Future<?>> scheduleDaily(Action task, String... timeArray) {
        return NQuery.of(timeArray).select(p -> scheduleDaily(task, Time.valueOf(p))).toList();
    }

    /**
     * 每天按指定时间执行
     *
     * @param task Action
     * @param time "HH:mm:ss"
     * @return Future
     */
    public static Future<?> scheduleDaily(@NonNull Action task, @NonNull Time time) {
        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay, "scheduleDaily");
    }

    public static Future<?> scheduleUntil(@NonNull Action task, @NonNull Func<Boolean> checkFunc, long delay) {
        $<Future<?>> future = $();
        future.v = schedule(() -> {
            if (checkFunc.invoke()) {
                future.v.cancel(true);
                return;
            }
            task.invoke();
        }, delay);
        return future.v;
    }

    public static Future<?> scheduleOnceAt(@NonNull Action task, @NonNull Date time) {
        long initDelay = time.getTime() - System.currentTimeMillis();
        return scheduleOnce(task, initDelay);
    }

    public static Future<?> scheduleOnce(@NonNull Action task, long delay) {
        return scheduler.schedule(() -> {
            try {
                task.invoke();
            } catch (Throwable e) {
                raiseUncaughtException("scheduleOnce", e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public static Future<?> schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public static Future<?> schedule(@NonNull Action task, long initialDelay, long delay, String taskName) {
        return scheduler.scheduleWithFixedDelay(new TaskScheduler.Task<>(isNull(taskName, Strings.EMPTY), null, () -> {
            task.invoke();
            return null;
        }), initialDelay, delay, TimeUnit.MILLISECONDS);
    }
}
