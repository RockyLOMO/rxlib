package org.rx.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.util.App.logError;
import static org.rx.util.App.logInfo;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/25
 */
public final class AsyncTask {
    private static class NamedRunnable<T> implements Runnable, Callable<T> {
        private final String name;
        private final Consumer<T> consumer;
        private final Function<T>

        @Override
        public void run() {

        }

        @Override
        public T call() throws Exception {
            return null;
        }
    }

    public static final AsyncTask    TaskFactory = new AsyncTask(4, 16, 10);

    private final ThreadPoolExecutor executor;

    AsyncTask(int minThreads, int maxThreads, int keepAliveMinutes) {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setUncaughtExceptionHandler((thread, ex) -> logError(new RuntimeException(ex), thread.getName()))
                .setNameFormat("AsyncTask-%d").build();
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(512);
        this.executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveMinutes, TimeUnit.MINUTES, queue,
                threadFactory, (p1, p2) -> {
                    logInfo("AsyncTask rejected task: %s", p1.toString());
                    p1.run();
                });
    }

    public <T> Future<T> run(Callable<T> task) {
        return executor.submit(task);
    }

    public void run(Runnable task) {
        executor.execute(task);
    }
}
