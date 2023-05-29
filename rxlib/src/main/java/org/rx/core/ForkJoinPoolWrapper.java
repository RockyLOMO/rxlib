package org.rx.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class ForkJoinPoolWrapper extends ForkJoinPool {
    static Runnable wrap(Runnable task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap Runnable {}", traceId);
        return () -> {
            ThreadPool.startTrace(traceId);
            try {
                task.run();
            } finally {
                ThreadPool.endTrace();
            }
        };
    }

    static <T> Callable<T> wrap(Callable<T> task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap Callable {}", traceId);
        return () -> {
            ThreadPool.startTrace(traceId);
            try {
                return task.call();
            } finally {
                ThreadPool.endTrace();
            }
        };
    }

    static <T> ForkJoinTask<T> wrap(ForkJoinTask<T> task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap ForkJoinTask {}", traceId);
        return ForkJoinTask.adapt(() -> {
            ThreadPool.startTrace(traceId);
            try {
                return task.join();
            } finally {
                ThreadPool.endTrace();
            }
        });
    }

    final ForkJoinPool delegate;

    public ForkJoinPoolWrapper() {
        delegate = ForkJoinPool.commonPool();
    }

    //todo ForkJoinPool.externalPush
//    public void externalPush(ForkJoinTask<?> task) {
//    }

    @Override
    public <T> T invoke(ForkJoinTask<T> task) {
        return delegate.invoke(wrap(task));
    }

    @Override
    public void execute(ForkJoinTask<?> task) {
        delegate.execute(wrap(task));
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(wrap(task));
    }

    @Override
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public ForkJoinTask<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        return delegate.invokeAll(Linq.from(tasks).select(p -> wrap(p)).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(Linq.from(tasks).select(p -> wrap(p)).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(Linq.from(tasks).select(p -> wrap(p)).toList(), timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(Linq.from(tasks).select(p -> wrap(p)).toList(), timeout, unit);
    }

    @Override
    public ForkJoinWorkerThreadFactory getFactory() {
        return delegate.getFactory();
    }

    @Override
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return delegate.getUncaughtExceptionHandler();
    }

    @Override
    public int getParallelism() {
        return delegate.getParallelism();
    }

    @Override
    public int getPoolSize() {
        return delegate.getPoolSize();
    }

    @Override
    public boolean getAsyncMode() {
        return delegate.getAsyncMode();
    }

    @Override
    public int getRunningThreadCount() {
        return delegate.getRunningThreadCount();
    }

    @Override
    public int getActiveThreadCount() {
        return delegate.getActiveThreadCount();
    }

    @Override
    public boolean isQuiescent() {
        return delegate.isQuiescent();
    }

    @Override
    public long getStealCount() {
        return delegate.getStealCount();
    }

    @Override
    public long getQueuedTaskCount() {
        return delegate.getQueuedTaskCount();
    }

    @Override
    public int getQueuedSubmissionCount() {
        return delegate.getQueuedSubmissionCount();
    }

    @Override
    public boolean hasQueuedSubmissions() {
        return delegate.hasQueuedSubmissions();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean isTerminating() {
        return delegate.isTerminating();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        return delegate.awaitQuiescence(timeout, unit);
    }
}
