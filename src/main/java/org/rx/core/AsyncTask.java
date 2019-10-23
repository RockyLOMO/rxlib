package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.List;
import java.util.concurrent.*;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.isNull;
import static org.rx.core.Contract.require;

@Slf4j
public final class AsyncTask {
    @RequiredArgsConstructor
    private static class NamedRunnable<T> implements Runnable, Callable<T> {
        private final String name;
        private final Func<T> callable;

        @SneakyThrows
        @Override
        public T call() {
            return callable.invoke();
        }

        @Override
        public void run() {
            call();
        }

        @Override
        public String toString() {
            return String.format("AsyncTask[%s]", name);
        }
    }

    public static final int ThreadCount = Runtime.getRuntime().availableProcessors() + 1;
    public static final AsyncTask TaskFactory = new AsyncTask(1, App.MaxInt, 4, new SynchronousQueue<>());
    @Getter
    private final ThreadPoolExecutor executor;
    private final Lazy<ScheduledExecutorService> scheduler;

//    private AsyncTask() {
//        this(ThreadCount, ThreadCount, 4, new LinkedBlockingQueue<>());
//    }

    private AsyncTask(int minThreads, int maxThreads, int keepAliveMinutes, BlockingQueue<Runnable> queue) {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> log.error("AsyncTask {}", thread.getName(), ex))
                .setNameFormat("AsyncTask-%d").build();
        RejectedExecutionHandler rejected = new ThreadPoolExecutor.CallerRunsPolicy();
        executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, queue, threadFactory, rejected);
        scheduler = new Lazy<>(() -> new ScheduledThreadPoolExecutor(1, threadFactory, rejected));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null);
    }

    public <T> Future<T> run(Func<T> task, String taskName) {
        require(task);

        return executor.submit((Callable<T>) new NamedRunnable<>(isNull(taskName, Strings.Empty), task));
    }

    public void run(Action task) {
        run(task, null);
    }

    public void run(Action task, String taskName) {
        require(task);

        executor.execute(new NamedRunnable<>(isNull(taskName, Strings.Empty), () -> {
            task.invoke();
            return null;
        }));
    }

    public Future schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public Future schedule(Action task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.getValue().scheduleWithFixedDelay(new NamedRunnable<>(isNull(taskName, Strings.Empty), () -> {
            task.invoke();
            return null;
        }), initialDelay, delay, TimeUnit.MILLISECONDS);
    }

    public List<Future> scheduleDaily(Action task, String... timeArray) {
        require(timeArray);

        return NQuery.of(timeArray).select(p -> scheduleDaily(task, p)).toList();
    }

    /**
     * 每天按指定时间执行
     *
     * @param task
     * @param time "HH:mm:ss"
     * @return
     */
    public Future scheduleDaily(Action task, String time) {
        require(task, time);

        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay, "scheduleDaily");
    }

    public Future scheduleOnce(Action task, long delay) {
        require(task);

        $<Future> future = $();
        future.v = scheduler.getValue().scheduleWithFixedDelay(() -> {
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
