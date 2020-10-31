package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import static org.rx.core.Contract.*;

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
                //后台线程异常
                return callable.invoke();
            } catch (Throwable e) {
                log.error("Task IGNORE", e);
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

    @Getter
    private static final ThreadPool executor = new ThreadPool();
    private static final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(executor.getCorePoolSize(), executor.getThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    public static CompletableFuture<Void> run(Action task) {
        return run(task, SUID.randomSUID().toString(), null);
    }

    public static CompletableFuture<Void> run(Action task, String taskName, ThreadPool.ExecuteFlag executeFlag) {
        require(task, taskName);

        Task<Void> t = new Task<>(taskName, executeFlag, () -> {
            task.invoke();
            return null;
        });
        return CompletableFuture.runAsync(t, executor);
//        executor.execute(t);
    }

    public static <T> CompletableFuture<T> run(Func<T> task) {
        return run(task, SUID.randomSUID().toString(), null);
    }

    public static <T> CompletableFuture<T> run(Func<T> task, String taskName, ThreadPool.ExecuteFlag executeFlag) {
        require(task, taskName);

        Task<T> t = new Task<>(taskName, executeFlag, task);
        return CompletableFuture.supplyAsync(t, executor);
//        return executor.submit((Callable<T>) t);
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
            try {
                if (checkFunc.invoke()) {
                    future.v.cancel(true);
                    return;
                }
                task.invoke();
            } catch (Exception e) {
                log.error("Task IGNORE", e);
            }
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

        $<Future<?>> future = $();
        future.v = scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.invoke();
            } catch (Throwable e) {
                log.warn("Task IGNORE", e);
            } finally {
                future.v.cancel(true);
            }
        }, delay, delay, TimeUnit.MILLISECONDS);//todo -1?
        return future.v;
    }

    public static Future<?> schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public static Future<?> schedule(Action task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.scheduleWithFixedDelay(new Task<>(isNull(taskName, Strings.EMPTY), null, () -> {
            try {
                task.invoke();
            } catch (Throwable e) {
                log.error("Task IGNORE", e);
            }
            return null;
        }), initialDelay, delay, TimeUnit.MILLISECONDS);
    }
}
