package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.*;
import org.rx.bean.$;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

import static org.rx.bean.$.$;
import static org.rx.core.App.proxy;
import static org.rx.core.Extends.ifNull;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WheelTimer extends AbstractExecutorService implements ScheduledExecutorService {
    @RequiredArgsConstructor
    class Task<T> implements TimerTask, TimeoutFuture<T> {
        final Object id;
        final TimeoutFlag flag;
        final Func<T> fn;
        final LongUnaryOperator nextDelayFn;
        long delay;
        long expiredTime;
        volatile Timeout timeout;
        volatile Future<T> future;
        long p0, p1, p2;

        @SneakyThrows
        @Override
        public synchronized void run(Timeout timeout) throws Exception {
            future = Tasks.run(() -> {
                boolean doContinue = flag == TimeoutFlag.PERIOD;
                try {
                    return fn.invoke();
                } finally {
                    if (ThreadPool.asyncContinueFlag(doContinue)) {
                        newTimeout(this, delay, timeout.timer());
                    } else if (id != null) {
                        hold.remove(id);
                    }
                }
            });
            notifyAll();
        }

        @Override
        public String toString() {
            return String.format("WheelTask-%s[%s]", ifNull(id, Strings.EMPTY), ifNull(flag, TimeoutFlag.SINGLE));
        }

        @Override
        public Timer timer() {
            return timeout.timer();
        }

        @Override
        public TimerTask task() {
            return timeout.task();
        }

        @Override
        public boolean isExpired() {
            return timeout.isExpired();
        }

        @Override
        public boolean isCancelled() {
            return timeout.isCancelled();
        }

        @Override
        public boolean cancel() {
            return cancel(true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (future != null) {
                future.cancel(mayInterruptIfRunning);
            }
            if (timeout != null) {
                //Timeout maybe null when parallel invoke tasks of the same id
                return timeout.cancel();
            }
            return true;
        }

        @Override
        public boolean isDone() {
            return future != null && future.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            synchronized (this) {
                if (future == null) {
                    wait();
                }
            }
            if (future == null) {
                throw new InterruptedException();
            }
            return future.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            synchronized (this) {
                if (future == null) {
                    wait(unit.convert(timeout, TimeUnit.MILLISECONDS));
                }
            }
            if (future == null) {
                throw new TimeoutException();
            }
            return future.get(timeout, unit);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expiredTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            if (!(o instanceof Task)) {
                return 0;
            }
            long otherExpiredTime = ((Task<?>) o).expiredTime;
            return Long.compare(expiredTime, otherExpiredTime);
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
        public void run(Timeout timeout) throws Exception {
        }
    }

    static final long TICK_DURATION = 100;
    final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("TIMER"), TICK_DURATION, TimeUnit.MILLISECONDS);
    final Map<Object, TimeoutFuture> hold = new ConcurrentHashMap<>();
    final EmptyTimeout nonTask = new EmptyTimeout();

    public TimeoutFuture<?> setTimeout(Action fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public TimeoutFuture<?> setTimeout(@NonNull Action fn, LongUnaryOperator nextDelay, Object taskId, TimeoutFlag flag) {
        return setTimeout(new Task<>(taskId, flag, fn.toFunc(), nextDelay));
    }

    public <T> TimeoutFuture<T> setTimeout(Func<T> fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public <T> TimeoutFuture<T> setTimeout(@NonNull Func<T> fn, LongUnaryOperator nextDelay, Object taskId, TimeoutFlag flag) {
        return setTimeout(new Task<>(taskId, flag, fn, nextDelay));
    }

    public TimeoutFuture<?> setTimeout(Action fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public TimeoutFuture<?> setTimeout(@NonNull Action fn, long delay, Object taskId, TimeoutFlag flag) {
        Task<?> task = new Task<>(taskId, flag, fn.toFunc(), null);
        task.delay = delay;
        return setTimeout(task);
    }

    public <T> TimeoutFuture<T> setTimeout(Func<T> fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public <T> TimeoutFuture<T> setTimeout(@NonNull Func<T> fn, long delay, Object taskId, TimeoutFlag flag) {
        Task<T> task = new Task<>(taskId, flag, fn, null);
        task.delay = delay;
        return setTimeout(task);
    }

    private <T> TimeoutFuture<T> setTimeout(Task<T> task) {
        if (task.id == null) {
            newTimeout(task, 0, timer);
            return task;
        }

        TimeoutFlag flag = task.flag;
        if (flag == null) {
            flag = TimeoutFlag.SINGLE;
        }
        if (flag == TimeoutFlag.SINGLE) {
            TimeoutFuture<T> ot = hold.get(task.id);
            if (ot != null) {
                return ot;
            }
        }

        TimeoutFuture<T> ot = hold.put(task.id, task);
        newTimeout(task, 0, timer);
        if (flag == TimeoutFlag.REPLACE && ot != null) {
            ot.cancel();
        }
        return task;
    }

    private <T> void newTimeout(Task<T> task, long initDelay, Timer timer) {
        if (task.nextDelayFn != null) {
            task.delay = task.nextDelayFn.applyAsLong(initDelay);
        }
        if (task.delay == Constants.TIMEOUT_INFINITE) {
            task.timeout = nonTask;
        } else {
            task.timeout = timer.newTimeout(task, task.delay, TimeUnit.MILLISECONDS);
        }
        task.expiredTime = System.currentTimeMillis() + task.delay;
    }

    //region adapter
    static final int M_0 = "isCancelled".hashCode(), M_1 = "cancel".hashCode();
    @Getter
    boolean shutdown;

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return setTimeout(command::run, unit.convert(delay, TimeUnit.MILLISECONDS));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return setTimeout(callable::call, unit.convert(delay, TimeUnit.MILLISECONDS));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        long initDelay = unit.convert(initialDelay, TimeUnit.MILLISECONDS);
        Task<?> t = (Task<?>) setTimeout(command::run, initDelay);
        AtomicBoolean cancel = new AtomicBoolean();
        ScheduledFuture<?> future = proxy(ScheduledFuture.class, (m, p) -> {
            int h = m.getName().hashCode();
            if (h == M_0) {
                return cancel.get();
            } else if (h == M_1) {
                cancel.set(true);
            }
            return p.fastInvoke(t);
        });

        long nextDelay = initDelay + period;
        long periodMillis = unit.convert(period, TimeUnit.MILLISECONDS);
        nextFixedRate(future, t, nextDelay, command, periodMillis);
        return future;
    }

    void nextFixedRate(ScheduledFuture<?> proxy, Task<?> future, long nextDelay, Runnable command, long period) {
        $<Task<?>> t = $();
        t.v = (Task<?>) setTimeout(() -> {
            if (!proxy.isCancelled()) {
                nextFixedRate(proxy, future, period - TICK_DURATION, command, period);

                Task<?> p = t.v;
                future.timeout = p.timeout;
                future.future = (CompletableFuture) p.future;
                future.delay = p.delay;
                future.expiredTime = p.expiredTime;
            }
            command.run();
        }, nextDelay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return setTimeout(command::run, d -> d == 0 ? initialDelay : unit.convert(period, TimeUnit.MILLISECONDS), null, TimeoutFlag.PERIOD);
    }

    @Override
    public void execute(Runnable command) {
        Tasks.run(command::run);
    }

    @Override
    public void shutdown() {
        timer.stop();
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        return Collections.emptyList();
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }
    //endregion
}
