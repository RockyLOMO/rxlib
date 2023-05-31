package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;
import org.rx.exception.InvalidException;

import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.function.Function;

@Slf4j
public class ForkJoinPoolWrapper extends ForkJoinPool {
    public static class TaskAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments) throws Throwable {
            final String sk = "";
            final int sl = 2, idx = 1;
            Properties props = System.getProperties();
            Object v = props.get(sk);
            Object[] share;
            Function<Object, Object> fn;
            if (!(v instanceof Object[]) || (share = (Object[]) v).length != sl
                    || (fn = (Function<Object, Object>) share[idx]) == null) {
                System.err.println("Advice empty fn");
                return;
            }

            Object task = arguments[0];
            Object[] newArgs = new Object[1];
            newArgs[0] = fn.apply(task);
            arguments = newArgs;
        }

        static boolean transformed;

        public synchronized static void transform() {
            if (transformed) {
                return;
            }
            transformed = true;
            Sys.checkAdviceShare(true);
            ByteBuddyAgent.install();
            new ByteBuddy()
                    .redefine(ForkJoinPool.class)
                    .visit(Advice.to(TaskAdvice.class).on(ElementMatchers.named("externalPush")))
                    .make()
                    .load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
        }
    }

    static Object wrap(Object task) {
        if (task instanceof ForkJoinTask) {
            return wrap((ForkJoinTask<?>) task);
        }
        if (task instanceof Runnable) {
            return wrap((Runnable) task);
        }
        if (task instanceof Callable) {
            return wrap((Callable<?>) task);
        }
        throw new InvalidException("Error task type {}", task);
    }

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
//            String tid = ThreadPool.startTrace(traceId);
//            log.info("wrap ForkJoinTask fn {}", tid);
            try {
                //join() will hang
                return task.invoke();
            } finally {
                ThreadPool.endTrace();
            }
        });
    }

    final ForkJoinPool delegate;

    //ForkJoinPool.externalPush
    ForkJoinPoolWrapper() {
        delegate = ForkJoinPool.commonPool();
    }

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
