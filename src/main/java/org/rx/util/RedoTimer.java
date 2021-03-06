package org.rx.util;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.App;
import org.rx.core.Tasks;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiAction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RedoTimer {
    @RequiredArgsConstructor
    private static class RedoTask implements TimerTask, Timeout {
        private final BiAction<Timeout> task;
        private final long timeoutMillis;
        private final AtomicInteger redoCount;
        private volatile boolean done;
        private volatile Timeout timeout;
        private Throwable lastError;

        @Override
        public void run(Timeout timeout) throws Exception {
            if (this.timeout != timeout) {
                throw new InvalidException("Timeout error");
            }
            if (done) {
                if (lastError != null) {
                    App.log("run", lastError);
                }
                return;
            }

            try {
                task.invoke(this);
            } catch (Throwable e) {
                lastError = e;
            }
            if (done || redoCount.decrementAndGet() <= 0) {
                if (lastError != null) {
                    App.log("run", lastError);
                }
                return;
            }
            this.timeout = timeout.timer().newTimeout(this, timeoutMillis, TimeUnit.MILLISECONDS);
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
            timeout.cancel();
            return done = true;
        }
    }

    private final HashedWheelTimer timer = new HashedWheelTimer(Tasks.pool().getThreadFactory());

    public Timeout setTimeout(BiAction<Timeout> task, long delayMillis) {
        return setTimeout(task, delayMillis, 1);
    }

    public Timeout setTimeout(BiAction<Timeout> task, long delayMillis, int redoCount) {
        RedoTask redoTask = new RedoTask(task, delayMillis, new AtomicInteger(redoCount));
        redoTask.timeout = timer.newTimeout(redoTask, delayMillis, TimeUnit.MILLISECONDS);
        return redoTask;
    }

    public Timeout runAndSetTimeout(BiAction<Timeout> task, long delayMillis) {
        return runAndSetTimeout(task, delayMillis, 1);
    }

    @SneakyThrows
    public Timeout runAndSetTimeout(BiAction<Timeout> task, long delayMillis, int redoCount) {
        RedoTask redoTask = new RedoTask(task, delayMillis, new AtomicInteger(redoCount + 1));
//        redoTask.timeout = timer.newTimeout(redoTask, 0, TimeUnit.MILLISECONDS);
        redoTask.timeout = new Timeout() {
            boolean cancel;

            @Override
            public Timer timer() {
                return timer;
            }

            @Override
            public TimerTask task() {
                return redoTask;
            }

            @Override
            public boolean isExpired() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return cancel;
            }

            @Override
            public boolean cancel() {
                return cancel = true;
            }
        };
        redoTask.run(redoTask.timeout);
        return redoTask;
    }
}
