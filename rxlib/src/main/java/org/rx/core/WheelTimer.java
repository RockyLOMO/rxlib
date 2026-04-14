package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class WheelTimer extends AbstractExecutorService implements ScheduledExecutorService {
    class Task<T> implements TimerTask, TimeoutFuture<T> {
        final Func<T> fn;
        final FlagsEnum<TimeoutFlag> flags;
        final Object id;
        final LongUnaryOperator nextDelayFn;
        final String traceId;
        final StackTraceElement[] stackTrace;
        final CountDownLatch createdLatch = new CountDownLatch(1);
        final AtomicBoolean published = new AtomicBoolean();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        long delay;
        long expiredTime;
        volatile Timeout timeout;
        volatile Future<T> future;
        volatile Throwable terminalError;

        Task(Func<T> fn, FlagsEnum<TimeoutFlag> flags, Object id, LongUnaryOperator nextDelayFn) {
            this.fn = fn;
            this.flags = flags == null ? TimeoutFlag.NONE.flags() : flags;
            this.id = id;
            this.nextDelayFn = nextDelayFn;
            this.traceId = ThreadPool.traceId();
            this.stackTrace = captureStackTrace();
        }

        @Override
        public void run(Timeout timeout) {
            this.timeout = timeout;
            if (cancelRequested.get() || !isCurrentHolder()) {
                removeHolder();
                publish();
                return;
            }

            try {
                Future<T> submitted = executor.submit(() -> {
                    beginTrace(traceId, stackTrace);
                    boolean doContinue = flags.has(TimeoutFlag.PERIOD);
                    try {
                        return fn.get();
                    } finally {
                        try {
                            onExecutionFinished(ThreadPool.continueFlag(doContinue), timeout.timer());
                        } finally {
                            endTrace();
                        }
                    }
                });
                future = submitted;
                publish();
                if (cancelRequested.get()) {
                    submitted.cancel(true);
                }
            } catch (Throwable e) {
                terminalError = e;
                removeHolder();
                publish();
            }
        }

        private void onExecutionFinished(boolean doContinue, Timer timer) {
            if (doContinue && !cancelRequested.get() && isCurrentHolder()) {
                try {
                    if (newTimeout(this, delay, timer)) {
                        return;
                    }
                } catch (Throwable e) {
                    terminalError = e;
                }
            }
            removeHolder();
        }

        private boolean isCurrentHolder() {
            return id == null || holder.get(id) == this;
        }

        private void removeHolder() {
            if (id != null) {
                holder.remove(id, this);
            }
        }

        private void publish() {
            if (published.compareAndSet(false, true)) {
                createdLatch.countDown();
            }
        }

        @Override
        public Timer timer() {
            return timeout == null ? timer : timeout.timer();
        }

        @Override
        public TimerTask task() {
            return this;
        }

        @Override
        public boolean isExpired() {
            return timeout != null && timeout.isExpired();
        }

        @Override
        public boolean isCancelled() {
            Future<T> current = future;
            return cancelRequested.get() || (current != null && current.isCancelled());
        }

        @Override
        public boolean cancel() {
            return cancel(true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            Future<T> currentFuture = future;
            boolean changed;
            if (flags.has(TimeoutFlag.PERIOD)) {
                changed = !cancelRequested.getAndSet(true);
            } else {
                changed = false;
            }

            Timeout currentTimeout = timeout;
            if (currentFuture == null) {
                if (currentTimeout == null || currentTimeout.cancel()) {
                    cancelRequested.set(true);
                    changed = true;
                }
            } else if (currentFuture.cancel(mayInterruptIfRunning)) {
                cancelRequested.set(true);
                changed = true;
            }

            if (currentTimeout != null) {
                changed = currentTimeout.cancel() || changed;
            }
            if (cancelRequested.get()) {
                removeHolder();
                publish();
            }
            return changed;
        }

        @Override
        public boolean isDone() {
            if (terminalError != null) {
                return true;
            }
            if (flags.has(TimeoutFlag.PERIOD)) {
                return cancelRequested.get();
            }
            Future<T> current = future;
            return current != null ? current.isDone() : cancelRequested.get();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            createdLatch.await();
            try {
                return resolveGetResult(0, null);
            } catch (TimeoutException e) {
                throw new ExecutionException(e);
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            long waitMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
            long deadline = System.currentTimeMillis() + waitMillis;
            if (!createdLatch.await(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException();
            }
            long remaining = Math.max(0L, deadline - System.currentTimeMillis());
            return resolveGetResult(remaining, TimeUnit.MILLISECONDS);
        }

        private T resolveGetResult(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (terminalError != null && future == null) {
                throw new ExecutionException(terminalError);
            }
            Future<T> current = future;
            if (current == null) {
                if (cancelRequested.get()) {
                    throw new CancellationException();
                }
                if (unit == null) {
                    throw new ExecutionException(new IllegalStateException("Timer task was not submitted"));
                }
                throw new TimeoutException();
            }
            if (unit == null) {
                return current.get();
            }
            return current.get(timeout, unit);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            if (expiredTime == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return unit.convert(expiredTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            long diff = getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS);
            return diff == 0 ? 0 : (diff < 0 ? -1 : 1);
        }

        @Override
        public String toString() {
            String hc = id != null ? id.toString() : Integer.toHexString(hashCode());
            return "TimeTask-" + hc + "[" + flags.getValue() + "]";
        }
    }

    final class PeriodicTask implements TimerTask, TimeoutFuture<Object> {
        final Runnable command;
        final long periodMillis;
        final boolean fixedRate;
        final CountDownLatch terminalLatch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final String traceId = ThreadPool.traceId();
        final StackTraceElement[] stackTrace = captureStackTrace();
        volatile Timeout timeout;
        volatile Future<?> future;
        volatile boolean cancelled;
        volatile Throwable terminalError;
        volatile long expiredTime;
        volatile long nextFireTime;

        PeriodicTask(Runnable command, long initialDelayMillis, long periodMillis, boolean fixedRate) {
            this.command = command;
            this.periodMillis = periodMillis;
            this.fixedRate = fixedRate;
            this.nextFireTime = System.currentTimeMillis() + Math.max(0L, initialDelayMillis);
        }

        void schedule(long delayMillis) {
            long safeDelay = Math.max(0L, delayMillis);
            expiredTime = System.currentTimeMillis() + safeDelay;
            timeout = timer.newTimeout(this, safeDelay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run(Timeout timeout) {
            this.timeout = timeout;
            if (cancelled) {
                signalComplete();
                return;
            }

            try {
                Future<?> submitted = executor.submit(() -> {
                    beginTrace(traceId, stackTrace);
                    try {
                        command.run();
                        scheduleNext();
                    } catch (Throwable e) {
                        terminalError = e;
                        signalComplete();
                        throw e;
                    } finally {
                        endTrace();
                    }
                });
                future = submitted;
                if (cancelled) {
                    submitted.cancel(true);
                }
            } catch (Throwable e) {
                terminalError = e;
                signalComplete();
            }
        }

        private void scheduleNext() {
            if (cancelled) {
                signalComplete();
                return;
            }

            try {
                long now = System.currentTimeMillis();
                long delayMillis;
                if (fixedRate) {
                    nextFireTime += periodMillis;
                    delayMillis = Math.max(0L, nextFireTime - now);
                } else {
                    nextFireTime = now + periodMillis;
                    delayMillis = periodMillis;
                }
                schedule(delayMillis);
            } catch (Throwable e) {
                terminalError = e;
                signalComplete();
            }
        }

        private void signalComplete() {
            if (completed.compareAndSet(false, true)) {
                terminalLatch.countDown();
            }
        }

        @Override
        public Timer timer() {
            return timeout == null ? timer : timeout.timer();
        }

        @Override
        public TimerTask task() {
            return this;
        }

        @Override
        public boolean isExpired() {
            return timeout != null && timeout.isExpired();
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean cancel() {
            return cancel(true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelled || terminalError != null) {
                return false;
            }
            cancelled = true;
            Timeout currentTimeout = timeout;
            if (currentTimeout != null) {
                currentTimeout.cancel();
            }
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(mayInterruptIfRunning);
            }
            signalComplete();
            return true;
        }

        @Override
        public boolean isDone() {
            return cancelled || terminalError != null;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            terminalLatch.await();
            return resolveTerminal();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (!terminalLatch.await(timeout, unit)) {
                throw new TimeoutException();
            }
            return resolveTerminal();
        }

        private Object resolveTerminal() throws ExecutionException {
            if (cancelled) {
                throw new CancellationException();
            }
            if (terminalError != null) {
                throw new ExecutionException(terminalError);
            }
            throw new IllegalStateException("Periodic task should not complete normally");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expiredTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            long diff = getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS);
            return diff == 0 ? 0 : (diff < 0 ? -1 : 1);
        }
    }

    class EmptyTimeout implements Timeout, TimerTask {
        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return this;
        }

        @Override
        public boolean isExpired() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean cancel() {
            return true;
        }

        @Override
        public void run(Timeout timeout) {
        }
    }

    static final long TICK_DURATION = 100;

    public static LongUnaryOperator dailyOperator(@NonNull Func<String> timeFn) {
        return d -> {
            long delay = DateTime.now().setTimePart(timeFn.get()).getTime() - System.currentTimeMillis();
            return delay > 0 ? delay : Constants.ONE_DAY_TOTAL_SECONDS * 1000 + delay;
        };
    }

    final ExecutorService executor;
    final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("TIMER", Thread.NORM_PRIORITY), TICK_DURATION, TimeUnit.MILLISECONDS);
    final EmptyTimeout nonTask = new EmptyTimeout();
    final Map<Object, TimeoutFuture<?>> holder = new java.util.concurrent.ConcurrentHashMap<>();
    @Getter
    volatile boolean shutdown;
    volatile boolean shutdownNow;

    public TimeoutFuture<?> getFutureById(Object taskId) {
        return holder.get(taskId);
    }

    public TimeoutFuture<?> setTimeout(Action fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public TimeoutFuture<?> setTimeout(@NonNull Action fn, LongUnaryOperator nextDelay, Object taskId, FlagsEnum<TimeoutFlag> flags) {
        return setTimeout(new Task<>(fn.toFunc(), flags, taskId, nextDelay));
    }

    public <T> TimeoutFuture<T> setTimeout(Func<T> fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public <T> TimeoutFuture<T> setTimeout(@NonNull Func<T> fn, LongUnaryOperator nextDelay, Object taskId, FlagsEnum<TimeoutFlag> flags) {
        return setTimeout(new Task<>(fn, flags, taskId, nextDelay));
    }

    public TimeoutFuture<?> setTimeout(Action fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public TimeoutFuture<?> setTimeout(@NonNull Action fn, long delay, Object taskId, FlagsEnum<TimeoutFlag> flags) {
        Task<?> task = new Task<>(fn.toFunc(), flags, taskId, null);
        task.delay = delay;
        return setTimeout(task);
    }

    public <T> TimeoutFuture<T> setTimeout(Func<T> fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public <T> TimeoutFuture<T> setTimeout(@NonNull Func<T> fn, long delay, Object taskId, FlagsEnum<TimeoutFlag> flags) {
        Task<T> task = new Task<>(fn, flags, taskId, null);
        task.delay = delay;
        return setTimeout(task);
    }

    private <T> TimeoutFuture<T> setTimeout(Task<T> task) {
        ensureRunning();
        if (task.id == null) {
            newTimeout(task, 0, timer);
            return task;
        }

        HolderRef<T> ref = new HolderRef<>();
        holder.compute(task.id, (k, current) -> {
            if (task.flags.has(TimeoutFlag.SINGLE) && isActive(current)) {
                ref.existing = (TimeoutFuture<T>) current;
                return current;
            }
            ref.replaced = current;
            return task;
        });
        if (ref.existing != null) {
            return ref.existing;
        }

        try {
            newTimeout(task, 0, timer);
        } catch (Throwable e) {
            holder.remove(task.id, task);
            task.terminalError = e;
            task.publish();
            throw e;
        }
        if (task.flags.has(TimeoutFlag.REPLACE) && ref.replaced != null && ref.replaced != task) {
            ref.replaced.cancel();
        }
        return task;
    }

    private boolean isActive(TimeoutFuture<?> future) {
        return future != null && !future.isCancelled() && !future.isDone();
    }

    private <T> boolean newTimeout(Task<T> task, long initDelay, Timer timer) {
        if (task.nextDelayFn != null) {
            task.delay = task.nextDelayFn.applyAsLong(initDelay);
        }
        if (task.delay == Constants.TIMEOUT_INFINITE) {
            task.timeout = nonTask;
            task.expiredTime = Long.MAX_VALUE;
            return true;
        }
        if (task.delay < 0) {
            task.timeout = nonTask;
            task.expiredTime = System.currentTimeMillis();
            return false;
        }
        task.timeout = timer.newTimeout(task, task.delay, TimeUnit.MILLISECONDS);
        task.expiredTime = System.currentTimeMillis() + task.delay;
        return true;
    }

    private StackTraceElement[] captureStackTrace() {
        RxConfig conf = RxConfig.INSTANCE;
        if (conf.trace.slowMethodElapsedMicros <= 0) {
            return null;
        }
        int threshold = conf.threadPool.slowMethodSamplingPercent;
        if (threshold <= 0 || ThreadLocalRandom.current().nextInt(0, 100) >= threshold) {
            return null;
        }
        return new Throwable().getStackTrace();
    }

    private void beginTrace(String traceId, StackTraceElement[] stackTrace) {
        ThreadPool.startTrace(traceId);
        ThreadPool.CTX_STACK_TRACE.set(stackTrace != null ? stackTrace : Boolean.TRUE);
    }

    private void endTrace() {
        ThreadPool.CTX_STACK_TRACE.remove();
        ThreadPool.endTrace();
    }

    private void ensureRunning() {
        if (shutdown) {
            throw new RejectedExecutionException("WheelTimer is shutdown");
        }
    }

    private void validatePeriodic(long periodMillis, String name) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
    }

    static final class HolderRef<T> {
        TimeoutFuture<T> existing;
        TimeoutFuture<?> replaced;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ensureRunning();
        return setTimeout(command::run, Math.max(0L, TimeUnit.MILLISECONDS.convert(delay, unit)));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ensureRunning();
        return setTimeout(callable::call, Math.max(0L, TimeUnit.MILLISECONDS.convert(delay, unit)));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ensureRunning();
        long initialDelayMillis = Math.max(0L, TimeUnit.MILLISECONDS.convert(initialDelay, unit));
        long periodMillis = TimeUnit.MILLISECONDS.convert(period, unit);
        validatePeriodic(periodMillis, "period");

        PeriodicTask periodicTask = new PeriodicTask(command, initialDelayMillis, periodMillis, true);
        periodicTask.schedule(initialDelayMillis);
        return periodicTask;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ensureRunning();
        long initialDelayMillis = Math.max(0L, TimeUnit.MILLISECONDS.convert(initialDelay, unit));
        long periodMillis = TimeUnit.MILLISECONDS.convert(period, unit);
        validatePeriodic(periodMillis, "delay");

        PeriodicTask periodicTask = new PeriodicTask(command, initialDelayMillis, periodMillis, false);
        periodicTask.schedule(initialDelayMillis);
        return periodicTask;
    }

    @Override
    public void execute(Runnable command) {
        ensureRunning();
        executor.execute(command);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        shutdownNow = true;
        for (TimeoutFuture<?> future : holder.values()) {
            future.cancel(true);
        }
        holder.clear();
        timer.stop();
        return Collections.emptyList();
    }

    @Override
    public boolean isTerminated() {
        return shutdownNow || (shutdown && holder.isEmpty());
    }

    @SneakyThrows
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        long deadline = System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(timeout, unit);
        while (!isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())));
        }
        return isTerminated();
    }
}
