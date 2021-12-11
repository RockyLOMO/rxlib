package org.rx.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.IdGenerator;
import org.rx.exception.ExceptionHandler;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.rx.core.App.isNull;

public class TaskScheduler extends ThreadPool {
    @RequiredArgsConstructor
    static class Task<T> implements IdentityRunnable, Callable<T>, Supplier<T> {
        final Object id;
        final RunFlag flag;
        final Func<T> fn;

        @Override
        public Object id() {
            return id;
        }

        @Override
        public RunFlag flag() {
            return flag;
        }

        @SneakyThrows
        @Override
        public T call() {
            try {
                return fn.invoke();
            } catch (Throwable e) {
                Container.get(ExceptionHandler.class).uncaughtException(toString(), e);
//                return null;
                throw e;
            }
        }

        @Override
        public void run() {
            call();
        }

        @Override
        public T get() {
            return call();
        }

        @Override
        public String toString() {
            return String.format("Task-%s[%s]", isNull(id, Strings.EMPTY), isNull(flag, RunFlag.CONCURRENT));
        }
    }

    public TaskScheduler(String poolName) {
        super(poolName);
    }

    public TaskScheduler(int coreThreads, int maxThreads, int queueCapacity, String poolName) {
        super(coreThreads, maxThreads, queueCapacity, poolName);
    }

    public CompletableFuture<Void> run(Action task) {
        return run(task, IdGenerator.DEFAULT.increment(), null);
    }

    public CompletableFuture<Void> run(@NonNull Action task, @NonNull Object taskId, RunFlag runFlag) {
        Task<Void> t = new Task<>(taskId, runFlag, () -> {
            task.invoke();
            return null;
        });
        return CompletableFuture.runAsync(t, this);
//        executor.execute(t);
    }

    public <T> CompletableFuture<T> run(Func<T> task) {
        return run(task, IdGenerator.DEFAULT.increment(), null);
    }

    public <T> CompletableFuture<T> run(@NonNull Func<T> task, @NonNull Object taskId, RunFlag runFlag) {
        Task<T> t = new Task<>(taskId, runFlag, task);
        return CompletableFuture.supplyAsync(t, this);
//        return executor.submit((Callable<T>) t);
    }
}
