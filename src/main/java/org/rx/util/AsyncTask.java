package org.rx.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.*;

import static org.rx.common.Contract.require;
import static org.rx.util.App.isNull;
import static org.rx.util.App.logError;
import static org.rx.util.App.logInfo;

public final class AsyncTask {
    private static class NamedRunnable implements Runnable, Callable {
        private final String   name;
        private final Runnable runnable;
        private final Callable callable;

        public NamedRunnable(String name, Runnable runnable, Callable callable) {
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
        public Object call() throws Exception {
            if (callable == null) {
                return null;
            }
            return callable.call();
        }

        @Override
        public String toString() {
            return String.format("AsyncTask[%s,%s]", name, isNull(runnable, callable).getClass().getSimpleName());
        }
    }

    public static final AsyncTask    TaskFactory = new AsyncTask(0, Integer.MAX_VALUE, 4, new SynchronousQueue<>());
    private static final int         ThreadCount = Runtime.getRuntime().availableProcessors() + 1;
    private final ThreadPoolExecutor executor;

    private AsyncTask() {
        this(ThreadCount, ThreadCount, 4, new LinkedBlockingQueue<>());
    }

    private AsyncTask(int minThreads, int maxThreads, int keepAliveMinutes, BlockingQueue<Runnable> queue) {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> logError(new RuntimeException(ex), thread.getName()))
                .setNameFormat("AsyncTask-%d").build();
        this.executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, queue,
                threadFactory, (p1, p2) -> {
                    logInfo("AsyncTask rejected task: %s", p1.toString());
                    p1.run();
                });
    }

    public <T> Future<T> run(Callable<T> task) {
        return run(task, null);
    }

    public <T> Future<T> run(Callable<T> task, String taskName) {
        require(task);

        return executor.submit(taskName != null ? (Callable<T>) new NamedRunnable(taskName, null, task) : task);
    }

    public void run(Runnable task) {
        run(task, null);
    }

    public void run(Runnable task, String taskName) {
        require(task);

        executor.execute(taskName != null ? new NamedRunnable(taskName, task, null) : task);
    }
}
