package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.function.PredicateAction;
import org.rx.util.function.PredicateFunc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongUnaryOperator;

public class WheelTimer {
    @RequiredArgsConstructor
    class Task<T> implements TimerTask, Timeout {
        final Object id;
        final PredicateFunc<T> fn;
        long delay;
        Timeout timeout;
        final T state;
        final LongUnaryOperator nextDelayFn;
        boolean prevContinue = true;

        @SneakyThrows
        @Override
        public void run(Timeout timeout) throws Exception {
            try {
                prevContinue = fn.invoke(state);
            } finally {
                if (prevContinue) {
                    if (nextDelayFn != null) {
                        delay = nextDelayFn.applyAsLong(delay);
                    }
                    this.timeout = timeout.timer().newTimeout(this, delay, TimeUnit.MILLISECONDS);
                } else if (id != null) {
                    hold.remove(id);
                }
            }
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
    }

    final HashedWheelTimer timer = new HashedWheelTimer(Tasks.pool().getThreadFactory());
    final Map<Object, Timeout> hold = new ConcurrentHashMap<>();

    public Timeout setTimeout(PredicateAction fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null, null);
    }

    public Timeout setTimeout(PredicateAction fn, LongUnaryOperator nextDelay, Object taskId, RunFlag flag) {
        Task<?> task = new Task<>(taskId, s -> fn.invoke(), null, nextDelay);
        task.delay = nextDelay.applyAsLong(0);
        return setTimeout(task, flag);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, LongUnaryOperator nextDelay) {
        return setTimeout(fn, nextDelay, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, LongUnaryOperator nextDelay, T state) {
        return setTimeout(fn, nextDelay, state, null, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, LongUnaryOperator nextDelay, T state, Object taskId, RunFlag flag) {
        Task<T> task = new Task<>(taskId, fn, state, nextDelay);
        task.delay = nextDelay.applyAsLong(0);
        return setTimeout(task, flag);
    }

    public Timeout setTimeout(PredicateAction fn, long delay) {
        return setTimeout(fn, delay, null, null);
    }

    public Timeout setTimeout(PredicateAction fn, long delay, Object taskId, RunFlag flag) {
        Task<?> task = new Task<>(taskId, s -> fn.invoke(), null, null);
        task.delay = delay;
        return setTimeout(task, flag);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay) {
        return setTimeout(fn, delay, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay, T state) {
        return setTimeout(fn, delay, state, null, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay, T state, Object taskId, RunFlag flag) {
        Task<T> task = new Task<>(taskId, fn, state, null);
        task.delay = delay;
        return setTimeout(task, flag);
    }

    private <T> Timeout setTimeout(Task<T> task, RunFlag flag) {
        if (flag == null) {
            flag = RunFlag.OVERRIDE;
        }

        if (flag == RunFlag.SINGLE && task.id != null) {
            Timeout ot = hold.get(task.id);
            if (ot != null) {
                return ot;
            }
        }

        task.timeout = timer.newTimeout(task, task.delay, TimeUnit.MILLISECONDS);
        if (task.id == null) {
            return task;
        }

        Timeout ot = hold.put(task.id, task);
        if (ot != null) {
            ot.cancel();
        }
        return task;
    }
}
