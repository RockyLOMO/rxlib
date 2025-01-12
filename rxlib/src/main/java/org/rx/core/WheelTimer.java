package org.rx.core;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.*;
import org.rx.bean.$;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

import static org.rx.bean.$.$;
import static org.rx.core.Sys.proxy;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class WheelTimer extends AbstractExecutorService implements ScheduledExecutorService {
    //schedule 抛出异常会终止
//    public final class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
//        final String poolName;
//
//        public ScheduledThreadPool() {
//            super(RxConfig.INSTANCE.threadPool.scheduleInitSize, ThreadPool.newThreadFactory("schedule"));
//            this.poolName = "schedule";
//
//            ThreadPool.SIZER.register(this, ThreadPool.DEFAULT_CPU_WATER_MARK);
//        }
//
//        @Override
//        public String toString() {
//            return poolName;
//        }
//    }

    class Task<T> implements TimerTask, TimeoutFuture<T> {
        final Func<T> fn;
        final FlagsEnum<TimeoutFlag> flags;
        final Object id;
        final LongUnaryOperator nextDelayFn;
        final String traceId;
        final StackTraceElement[] stackTrace;
        long delay;
        long expiredTime;
        volatile Timeout timeout;
        volatile Future<T> future;
        long p0, p1;

        Task(Func<T> fn, FlagsEnum<TimeoutFlag> flags, Object id, LongUnaryOperator nextDelayFn) {
            if (flags == null) {
                flags = TimeoutFlag.NONE.flags();
            }
            RxConfig conf = RxConfig.INSTANCE;
            if (conf.trace.slowMethodElapsedMicros > 0 && ThreadLocalRandom.current().nextInt(0, 100) < conf.threadPool.slowMethodSamplingPercent) {
                stackTrace = new Throwable().getStackTrace();
            } else {
                stackTrace = null;
            }

            this.fn = fn;
            this.flags = flags;
            this.id = id;
            this.nextDelayFn = nextDelayFn;
            traceId = ThreadPool.traceId();
        }

        @SneakyThrows
        @Override
        public synchronized void run(Timeout timeout) throws Exception {
            ThreadPool.startTrace(traceId);
            ThreadPool.CTX_STACK_TRACE.set(stackTrace != null ? stackTrace : Boolean.TRUE);
            try {
                future = executor.submit(() -> {
                    boolean doContinue = flags.has(TimeoutFlag.PERIOD);
                    try {
                        return fn.get();
                    } finally {
                        if (ThreadPool.continueFlag(doContinue)) {
                            newTimeout(this, delay, timeout.timer());
                        } else if (id != null) {
                            holder.remove(id);
                        }
                    }
                });
            } finally {
                ThreadPool.CTX_STACK_TRACE.remove();
                ThreadPool.endTrace();
            }
            notifyAll();
        }

        @Override
        public String toString() {
            String hc = id != null ? id.toString() : Integer.toHexString(hashCode());
            return String.format("TimeTask-%s[%s]", hc, flags.getValue());
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
                    wait(TimeUnit.MILLISECONDS.convert(timeout, unit));
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
    static final Map<Object, TimeoutFuture> holder = new ConcurrentHashMap<>();

    public static LongUnaryOperator dailyOperator(@NonNull Func<String> timeFn) {
        return d -> {
            long delay = DateTime.now().setTimePart(timeFn.get()).getTime() - System.currentTimeMillis();
            return delay > 0 ? delay : Constants.ONE_DAY_TOTAL_SECONDS * 1000 + delay;
        };
    }

    final ExecutorService executor;
    final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("TIMER", Thread.NORM_PRIORITY), TICK_DURATION, TimeUnit.MILLISECONDS);
    final EmptyTimeout nonTask = new EmptyTimeout();

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
        if (task.id == null) {
            newTimeout(task, 0, timer);
            return task;
        }

        FlagsEnum<TimeoutFlag> flags = task.flags;
        if (flags.has(TimeoutFlag.SINGLE)) {
            TimeoutFuture<T> ot = holder.get(task.id);
            if (ot != null) {
                return ot;
            }
        }

        TimeoutFuture<T> ot = holder.put(task.id, task);
        newTimeout(task, 0, timer);
        if (flags.has(TimeoutFlag.REPLACE) && ot != null) {
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
    static final String M_0 = "isCancelled", M_1 = "cancel";
    @Getter
    boolean shutdown;

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return setTimeout(command::run, TimeUnit.MILLISECONDS.convert(delay, unit));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return setTimeout(callable::call, TimeUnit.MILLISECONDS.convert(delay, unit));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        long initDelay = TimeUnit.MILLISECONDS.convert(initialDelay, unit);
        Task<?> t = (Task<?>) setTimeout(command::run, initDelay);
        AtomicBoolean cancel = new AtomicBoolean();
        ScheduledFuture<?> future = proxy(ScheduledFuture.class, (m, p) -> {
            if (Strings.hashEquals(m.getName(), M_0)) {
                return cancel.get();
            } else if (Strings.hashEquals(m.getName(), M_1)) {
                cancel.set(true);
            }
            return p.fastInvoke(t);
        });

        long nextDelay = initDelay + period;
        long periodMillis = TimeUnit.MILLISECONDS.convert(period, unit);
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
                future.future = (Future) p.future;
                future.delay = p.delay;
                future.expiredTime = p.expiredTime;
            }
            command.run();
        }, nextDelay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return setTimeout(command::run, d -> d == 0 ? initialDelay : TimeUnit.MILLISECONDS.convert(period, unit), null, Constants.TIMER_PERIOD_FLAG);
    }

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    @Override
    public void shutdown() {
        timer.stop();
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
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
