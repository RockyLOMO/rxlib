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
import static org.rx.core.Contract.*;

@Slf4j
public final class ThreadExecutor {
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
            return String.format("AsyncTask-%s", name);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class WorkQueue<T> extends LinkedTransferQueue<T> {
        private final ThreadPoolExecutor executor;

        public boolean force(Runnable o) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Executor not running, can't force a command into the queue");
            }
            // forces the item onto the queue, to be used if the task is rejected
            return super.offer(o);
        }


    }

    public class  xx extends ThreadPoolExecutor{
        @Override
        public void execute(Runnable command) {
            super.execute(command);
        }
    }

    public static final int CpuIntensiveThreads = Runtime.getRuntime().availableProcessors() + 1;
    public static final int IoIntensiveThreads = Runtime.getRuntime().availableProcessors() * 2;
    public static final ThreadExecutor TaskFactory = new ThreadExecutor(IoIntensiveThreads, IoIntensiveThreads, 0, new LinkedTransferQueue<>());
    @Getter
    private final ThreadPoolExecutor executor;
    private final Lazy<ScheduledExecutorService> scheduler;

    private ThreadExecutor(int minThreads, int maxThreads, int keepAliveMinutes, BlockingQueue<Runnable> queue) {
        new LinkedTransferQueue<>();
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> log.error(thread.getName(), ex))
                .setNameFormat("AsyncTask-%d").build();
        RejectedExecutionHandler rejected = (task, executor) -> {
            if (executor.isShutdown()) {
                task.run();
                return;
            }
            if (!tryAs(executor.getQueue(), LinkedTransferQueue.class, q -> q.transfer(task))) {
                task.run();
            }
            new SynchronousQueue();
        };
        executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, queue, threadFactory, rejected);
        scheduler = new Lazy<>(() -> new ScheduledThreadPoolExecutor(minThreads, threadFactory, rejected));
    }

    public void run(Action task) {
        run(task, null);
    }

    public void run(Action task, String taskName) {
        require(task);

        executor.execute(new NamedRunnable<>(isNull(taskName, Strings.EMPTY), () -> {
            task.invoke();
            return null;
        }));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null);
    }

    public <T> Future<T> run(Func<T> task, String taskName) {
        require(task);

        return executor.submit((Callable<T>) new NamedRunnable<>(isNull(taskName, Strings.EMPTY), task));
    }

    public Future schedule(Action task, long delay) {
        return schedule(task, delay, delay, null);
    }

    public Future schedule(Action task, long initialDelay, long delay, String taskName) {
        require(task);

        return scheduler.getValue().scheduleWithFixedDelay(new NamedRunnable<>(isNull(taskName, Strings.EMPTY), () -> {
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
