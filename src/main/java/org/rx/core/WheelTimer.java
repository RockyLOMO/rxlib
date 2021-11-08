package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.*;
import org.rx.util.function.PredicateAction;
import org.rx.util.function.PredicateFunc;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongUnaryOperator;

import static org.rx.core.App.isNull;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WheelTimer {
    public interface TimeoutFuture extends Timeout, Future<Void> {
    }

    @RequiredArgsConstructor
    class Task<T> implements TimerTask, TimeoutFuture {
        final Object id;
        final TimeoutFlag flag;
        final PredicateFunc<T> fn;
        long delay;
        final T state;
        final LongUnaryOperator nextDelayFn;
        Timeout timeout;
        CompletableFuture<Void> future;

        @SneakyThrows
        @Override
        public synchronized void run(Timeout timeout) throws Exception {
            future = Tasks.run(() -> {
                boolean doContinue = flag == TimeoutFlag.PERIOD;
                try {
                    doContinue = fn.invoke(state);
                } finally {
                    if (doContinue) {
                        if (nextDelayFn != null) {
                            delay = nextDelayFn.applyAsLong(delay);
                        }
                        this.timeout = timeout.timer().newTimeout(this, delay, TimeUnit.MILLISECONDS);
                    } else if (id != null) {
                        hold.remove(id);
                    }
                }
            });
            notifyAll();
        }

        @Override
        public String toString() {
            return String.format("WheelTask-%s[%s]", isNull(id, Strings.EMPTY), isNull(flag, TimeoutFlag.SINGLE));
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
            return timeout.cancel();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (future != null) {
                future.cancel(mayInterruptIfRunning);
            }
            return timeout.cancel();
        }

        @Override
        public boolean isDone() {
            return future != null && future.isDone();
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
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
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            synchronized (this) {
                if (future == null) {
                    wait(unit.toMillis(timeout));
                }
            }
            if (future == null) {
                throw new TimeoutException();
            }
            return future.get(timeout, unit);
        }
    }

    final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("TIMER"));
    final Map<Object, TimeoutFuture> hold = new ConcurrentHashMap<>();

    public TimeoutFuture setTimeout(PredicateAction fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public TimeoutFuture setTimeout(@NonNull PredicateAction fn, LongUnaryOperator nextDelay, Object taskId, TimeoutFlag flag) {
        Task<?> task = new Task<>(taskId, flag, s -> fn.invoke(), null, nextDelay);
        task.delay = nextDelay.applyAsLong(0);
        return setTimeout(task);
    }

    public <T> TimeoutFuture setTimeout(PredicateFunc<T> fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null);
    }

    public <T> TimeoutFuture setTimeout(PredicateFunc<T> fn, LongUnaryOperator nextDelay, T state) {
        return setTimeout(fn, nextDelay, state, null, null);
    }

    public <T> TimeoutFuture setTimeout(@NonNull PredicateFunc<T> fn, LongUnaryOperator nextDelay, T state, Object taskId, TimeoutFlag flag) {
        Task<T> task = new Task<>(taskId, flag, fn, state, nextDelay);
        task.delay = nextDelay.applyAsLong(0);
        return setTimeout(task);
    }

    public TimeoutFuture setTimeout(PredicateAction fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public TimeoutFuture setTimeout(@NonNull PredicateAction fn, long delay, Object taskId, TimeoutFlag flag) {
        Task<?> task = new Task<>(taskId, flag, s -> fn.invoke(), null, null);
        task.delay = delay;
        return setTimeout(task);
    }

    public <T> TimeoutFuture setTimeout(PredicateFunc<T> fn, long delay) {
        return setTimeout(fn, delay, null);
    }

    public <T> TimeoutFuture setTimeout(PredicateFunc<T> fn, long delay, T state) {
        return setTimeout(fn, delay, state, null, null);
    }

    public <T> TimeoutFuture setTimeout(@NonNull PredicateFunc<T> fn, long delay, T state, Object taskId, TimeoutFlag flag) {
        Task<T> task = new Task<>(taskId, flag, fn, state, null);
        task.delay = delay;
        return setTimeout(task);
    }

    private <T> TimeoutFuture setTimeout(Task<T> task) {
        TimeoutFlag flag = task.flag;
        if (flag == null) {
            flag = TimeoutFlag.SINGLE;
        }

        if (flag == TimeoutFlag.SINGLE && task.id != null) {
            TimeoutFuture ot = hold.get(task.id);
            if (ot != null && !ot.isDone()) {
                return ot;
            }
        }

        task.timeout = timer.newTimeout(task, task.delay, TimeUnit.MILLISECONDS);
        if (task.id == null) {
            return task;
        }

        TimeoutFuture ot = hold.put(task.id, task);
        if (flag == TimeoutFlag.REPLACE && ot != null) {
            ot.cancel();
        }
        return task;
    }
}
