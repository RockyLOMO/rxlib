package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.IdGenerator;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class TaskScheduler extends ThreadPool {
    @RequiredArgsConstructor
    protected static class Task<T> implements ThreadPool.NamedRunnable, Callable<T>, Supplier<T> {
        @Getter
        private final String name;
        @Getter
        private final RunFlag flag;
        private final Func<T> callable;

        @Override
        public T get() {
            return call();
        }

        @SneakyThrows
        @Override
        public T call() {
            try {
                return callable.invoke();
            } catch (Throwable e) {
                Tasks.raiseUncaughtException("RunFlag={}", flag, e);
//                return null;
                throw e;
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

    public TaskScheduler(String poolName) {
        this(ThreadPool.CPU_THREADS + 1, poolName);
    }

    public TaskScheduler(int coreThreads, String poolName) {
        super(coreThreads, poolName);
    }

    public CompletableFuture<Void> run(Action task) {
        return run(task, String.valueOf(IdGenerator.DEFAULT.increment()), null);
    }

    public CompletableFuture<Void> run(@NonNull Action task, @NonNull String taskName, RunFlag runFlag) {
        Task<Void> t = new Task<>(taskName, runFlag, () -> {
            task.invoke();
            return null;
        });
        return CompletableFuture.runAsync(t, this);
//        executor.execute(t);
    }

    public <T> CompletableFuture<T> run(Func<T> task) {
        return run(task, String.valueOf(IdGenerator.DEFAULT.increment()), null);
    }

    public <T> CompletableFuture<T> run(@NonNull Func<T> task, @NonNull String taskName, RunFlag runFlag) {
        Task<T> t = new Task<>(taskName, runFlag, task);
        return CompletableFuture.supplyAsync(t, this);
//        return executor.submit((Callable<T>) t);
    }
}
