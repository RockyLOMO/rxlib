package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.$;
import org.rx.bean.DateTime;
import org.rx.bean.SUID;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

//ExecutorCompletionService
@Slf4j
public final class Tasks {
    @RequiredArgsConstructor
    private static class Task<T> implements ThreadPool.NamedRunnable, Callable<T>, Supplier<T> {
        @Getter
        private final String name;
        @Getter
        private final ThreadPool.ExecuteFlag flag;
        private final Func<T> callable;

        @Override
        public T get() {
            return call();
        }

        @Override
        public T call() {
            try {
                return callable.invoke();
            } catch (Throwable e) {
                raiseUncaughtException(e);
                return null;
            }
        }

        @Override
        public void run() {
            call();
        }

        @Override
        public String toString() {
            return String.format("Task-%s[%s]", name, getFlag());
        }
    }

    //随机负载，如果methodA wait methodA，methodA在执行等待，methodB在threadPoolQueue，那么会出现假死现象。
    private static final ThreadPool[] loadBalancing;
    //HashedWheelTimer
    private static final ScheduledExecutorService scheduler;
    private static final FastThreadLocal<Boolean> raiseFlag = new FastThreadLocal<>();

    static {
        int poolCount = App.getConfig().getThreadPoolCount();
        int coreSize = Math.max(1, ThreadPool.CPU_THREADS / poolCount);
        loadBalancing = new ThreadPool[poolCount];
        for (int i = 0; i < poolCount; i++) {
            loadBalancing[i] = new ThreadPool(coreSize);
        }
        scheduler = new ScheduledThreadPoolExecutor(1, loadBalancing[0].getThreadFactory());
    }

    public static ThreadPool getExecutor() {
        return loadBalancing[ThreadLocalRandom.current().nextInt(0, loadBalancing.length)];
    }

    public static void raiseUncaughtException(Throwable e) {
        if (BooleanUtils.isTrue(raiseFlag.getIfExists())) {
            return;
        }
        raiseFlag.set(Boolean.TRUE);
        try {
            Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
            if (handler == null) {
                log.error("UncaughtException", e);
                return;
            }
            handler.uncaughtException(Thread.currentThread(), e);
        } catch (Throwable ie) {
            log.error("UncaughtException", ie);
        } finally {
            raiseFlag.remove();
        }
    }

    public static CompletableFuture<Void> run(Action task) {
        return run(task, SUID.randomSUID().toString(), null);
    }

    public static CompletableFuture<Void> run(Action task, String taskName, ThreadPool.ExecuteFlag executeFlag) {
        require(task, taskName);

        Task<Void> t = new Task<>(taskName, executeFlag, () -> {
            task.invoke();
            return null;
        });
        return CompletableFuture.runAsync(t, getExecutor());
//        executor.execute(t);
    }

    public static <T> CompletableFuture<T> run(Func<T> task) {
        return run(task, SUID.randomSUID().toString(), null);
    }

    public static <T> CompletableFuture<T> run(Func<T> task, String taskName, ThreadPool.ExecuteFlag executeFlag) {
        require(task, taskName);

        Task<T> t = new Task<>(taskName, executeFlag, task);
        return CompletableFuture.supplyAsync(t, getExecutor());
//        return executor.submit((Callable<T>) t);
    }

    public CompletableFuture<?> anyOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return CompletableFuture.completedFuture(null);
        }
        //Lambda method ref -> 对select的引用不明确
        return CompletableFuture.anyOf(NQuery.of(tasks).select(p -> run(p)).toArray());
    }

    public <T> CompletableFuture<T> anyOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return CompletableFuture.completedFuture(null);
        }

        return (CompletableFuture<T>) CompletableFuture.anyOf(NQuery.of(tasks).select(p -> run(p)).toArray());
    }

    public CompletableFuture<?> allOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(NQuery.of(tasks).select(p -> run(p)).toArray());
    }

    public <T> CompletableFuture<T> allOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return CompletableFuture.completedFuture(null);
        }

        return (CompletableFuture<T>) CompletableFuture.allOf(NQuery.of(tasks).select(p -> run(p)).toArray());
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
    public static Future<?> scheduleDaily(Action task, Time time) {
        require(task, time);

        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay, "scheduleDaily");
    }

    public static Future<?> scheduleUntil(Action task, Func<Boolean> checkFunc, long delay) {
        require(task, checkFunc);

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

    public static Future<?> scheduleOnceAt(Action task, Date time) {
        require(task, time);

        long initDelay = time.getTime() - System.currentTimeMillis();
        return scheduleOnce(task, initDelay);
    }

    public static Future<?> scheduleOnce(Action task, long delay) {
        require(task);

        return scheduler.schedule(() -> {
            try {
                task.invoke();
            } catch (Throwable e) {
                raiseUncaughtException(e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public static Future<?> schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public static Future<?> schedule(Action task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.scheduleWithFixedDelay(new Task<>(isNull(taskName, Strings.EMPTY), null, () -> {
            task.invoke();
            return null;
        }), initialDelay, delay, TimeUnit.MILLISECONDS);
    }
}
