package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.function.PredicateFunc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class WheelTimer {
    @RequiredArgsConstructor
    static class Task<T> implements TimerTask {
        final PredicateFunc<T> fn;
        final long delay;
        final T state;

        @SneakyThrows
        @Override
        public void run(Timeout timeout) throws Exception {
            boolean doContinue = fn.invoke(state);
            if (!doContinue) {
                return;
            }
            timeout.timer().newTimeout(this, delay, TimeUnit.MILLISECONDS);
        }
    }

    final HashedWheelTimer timer = new HashedWheelTimer(Tasks.pool().getThreadFactory());
    final Map<String, Timeout> timeouts = new ConcurrentHashMap<>();

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay) {
        return setTimeout(fn, delay, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay, T state) {
        return setTimeout(fn, delay, state, null);
    }

    public <T> Timeout setTimeout(PredicateFunc<T> fn, long delay, T state, String taskName) {
        Timeout nt = timer.newTimeout(new Task<>(fn, delay, state), delay, TimeUnit.MILLISECONDS);
        if (taskName == null) {
            return nt;
        }

        Timeout ot = timeouts.put(taskName, nt);
        if (ot != null) {
            ot.cancel();
        }
        return nt;
    }
}
