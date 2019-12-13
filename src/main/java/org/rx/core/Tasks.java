package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.List;
import java.util.concurrent.*;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.*;

@Slf4j
public final class Tasks {
    @RequiredArgsConstructor
    private static class Task<T> implements Runnable, Callable<T> {
        private final String name;
        private final Func<T> callable;

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
            return String.format("Task-%s", name);
        }
    }

    @Getter
    private static final ThreadPool executor = new ThreadPool();
    private static final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(executor.getCorePoolSize(), executor.getThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    public static void run(Action task) {
        run(task, null);
    }

    public static void run(Action task, String taskName) {
        require(task);

        executor.execute(new Task<>(isNull(taskName, Strings.EMPTY), () -> {
            task.invoke();
            return null;
        }));
    }

    public static <T> Future<T> run(Func<T> task) {
        return run(task, null);
    }

    public static <T> Future<T> run(Func<T> task, String taskName) {
        require(task);

        return executor.submit((Callable<T>) new Task<>(isNull(taskName, Strings.EMPTY), task));
    }

    public static Future schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public static Future schedule(Action task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.scheduleWithFixedDelay(new Task<>(isNull(taskName, Strings.EMPTY), () -> {
            try {
                task.invoke();
            } catch (Throwable e) {
                log.error("Task IGNORE", e);
            }
            return null;
        }), initialDelay, delay, TimeUnit.MILLISECONDS);
    }


    public static List<Future> scheduleDaily(Action task, String... timeArray) {
        require((Object) timeArray);

        return NQuery.of(timeArray).select(p -> scheduleDaily(task, p)).toList();
    }

    /**
     * 每天按指定时间执行
     *
     * @param task Action
     * @param time "HH:mm:ss"
     * @return Future
     */
    public static Future scheduleDaily(Action task, String time) {
        require(task, time);

        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay, "scheduleDaily");
    }

    public static Future scheduleUntil(Action task, Func<Boolean> checkFunc, long delay) {
        require(task, checkFunc);

        $<Future> future = $();
        future.v = schedule(() -> {
            if (checkFunc.invoke()) {
                future.v.cancel(true);
                return;
            }
            task.invoke();
        }, delay);
        return future.v;
    }

    public static Future scheduleOnce(Action task, long delay) {
        require(task);

        $<Future> future = $();
        future.v = scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.invoke();
                future.v.cancel(true);
            } catch (Throwable e) {
                log.warn("scheduleOnce", e);
            }
        }, delay, delay, TimeUnit.MILLISECONDS);
        return future.v;
    }
}
