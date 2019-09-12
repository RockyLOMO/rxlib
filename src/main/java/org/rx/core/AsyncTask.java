package org.rx.core;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.util.function.Func;

import java.util.List;
import java.util.concurrent.*;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.isNull;
import static org.rx.core.Contract.require;

@Slf4j
public final class AsyncTask {
    private static class NamedRunnable implements Runnable, Callable {
        private final String name;
        private final Runnable runnable;
        private final Func callable;

        public NamedRunnable(String name, Runnable runnable, Func callable) {
            this.name = name;
            this.runnable = runnable;
            this.callable = callable;
        }

        @Override
        public void run() {
            if (runnable == null) {
                return;
            }
            runnable.run();
        }

        @Override
        public Object call() {
            if (callable == null) {
                return null;
            }
            return callable.invoke();
        }

        @Override
        public String toString() {
            return String.format("AsyncTask[%s,%s]", name, isNull(runnable, callable).getClass().getSimpleName());
        }
    }

    public static final int ThreadCount = Runtime.getRuntime().availableProcessors() + 1;
    public static final AsyncTask TaskFactory = new AsyncTask(1, App.MaxSize, 4, new SynchronousQueue<>());
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

        return executor.submit((Callable<T>) new NamedRunnable(taskName, null, task));
    }

    public void run(Runnable task) {
        run(task, null);
    }

    public void run(Runnable task, String taskName) {
        require(task);

        executor.execute(taskName != null ? new NamedRunnable(taskName, task, null) : task);
    }

    public Future schedule(Runnable task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public Future schedule(Runnable task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.getValue().scheduleWithFixedDelay(new NamedRunnable(taskName, task, null), initialDelay, delay, TimeUnit.MILLISECONDS);
    }

    public List<Future> scheduleDaily(Runnable task, String... timeArray) {
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
    public Future scheduleDaily(Runnable task, String time) {
        require(task, time);

        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay, "scheduleDaily");
    }

    public Future scheduleOnce(Runnable task, long delay) {
        require(task);

        $<Future> future = $();
        future.v = scheduler.getValue().scheduleWithFixedDelay(() -> {
            task.run();
            try {
                future.v.cancel(true);
            } catch (Exception e) {
                log.warn("setTimeout", e);
            }
        }, delay, delay, TimeUnit.MILLISECONDS);
        return future.v;
    }
}
