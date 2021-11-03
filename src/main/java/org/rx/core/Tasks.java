package org.rx.core;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

import static org.rx.bean.$.$;

//ExecutorCompletionService
//Java 11 and ForkJoinPool.commonPool() class loading issue
@Slf4j
public final class Tasks {
    static class ScheduledThreadPool extends ScheduledThreadPoolExecutor {
        final int minSize, maxSize;

        public ScheduledThreadPool(ThreadFactory threadFactory) {
            this(0, ThreadPool.CPU_THREADS * 4, threadFactory);
        }

        public ScheduledThreadPool(int minSize, int maxSize, ThreadFactory threadFactory) {
            super(minSize, threadFactory, (r, executor) -> log.error("scheduler reject"));
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            int size = getCorePoolSize();
            if (size < maxSize && getActiveCount() >= size) {
                size = Math.max(2, ++size);
                setCorePoolSize(size);
                log.debug("grow pool size {}", size);
            }

            super.beforeExecute(t, r);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);

            int size = getCorePoolSize();
            float idle;
            if (size > minSize && (idle = (float) getActiveCount() / size) <= 0.5f) {
                setCorePoolSize(--size);
                log.debug("reduce pool size {}, idle={}", size, idle);
            }
        }
    }

    private static final int POOL_COUNT = 2;
    //随机负载，如果methodA wait methodA，methodA在执行等待，methodB在threadPoolQueue，那么会出现假死现象。
    private static final List<TaskScheduler> replicas;
    private static final WheelTimer wheelTimer;
    private static final ScheduledThreadPoolExecutor scheduler;
    private static final Queue<Action> shutdownActions = new ConcurrentLinkedQueue<>();

    static {
        int coreSize = Math.max(1, ThreadPool.CPU_THREADS / POOL_COUNT);
        replicas = new CopyOnWriteArrayList<>();
        for (int i = 0; i < POOL_COUNT; i++) {
            replicas.add(new TaskScheduler(coreSize, String.valueOf(i)));
        }
        wheelTimer = new WheelTimer();
        scheduler = new ScheduledThreadPool(replicas.get(0).getThreadFactory());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Action fn;
            while ((fn = shutdownActions.poll()) != null) {
                try {
                    fn.invoke();
                } catch (Throwable e) {
                    log.warn("ShutdownHook", e);
                }
            }
        }));
    }

    public static TaskScheduler pool() {
        return replicas.get(ThreadLocalRandom.current().nextInt(0, POOL_COUNT));
    }

    public static WheelTimer timer() {
        return wheelTimer;
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public static void addShutdownHook(Action fn) {
        shutdownActions.offer(fn);
    }

    @SneakyThrows
    @SafeVarargs
    public static <T> T sequentialRetry(Func<T>... funcs) {
        Throwable last = null;
        for (Func<T> func : funcs) {
            try {
                return func.invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    @SneakyThrows
    @SafeVarargs
    public static <T> T randomRetry(Func<T>... funcs) {
        int mid = ThreadLocalRandom.current().nextInt(0, funcs.length);
        Throwable last = null;
        for (int i = 0; i < mid; i++) {
            try {
                return funcs[i].invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        for (int i = mid; i < funcs.length; i++) {
            try {
                return funcs[i].invoke();
            } catch (Throwable e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    @SneakyThrows
    public static <T> T await(Future<T> future) {
        if (future instanceof CompletableFuture) {
            return ((CompletableFuture<T>) future).join();
        }
        return future.get();
    }

    public static <T> T awaitQuietly(Func<T> func, long millis) {
        return awaitQuietly(run(func), millis);
    }

    public static <T> T awaitQuietly(Future<T> future, long millis) {
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            //catch +1 ?
            log.warn("awaitNow {} timeout", Reflects.stackClass(2).getName());
        } catch (Exception e) {
            log.warn("awaitNow", e);
        }
        return null;
    }

    public static CompletableFuture<Void> run(Action task) {
        return pool().run(task);
    }

    public static CompletableFuture<Void> run(Action task, String taskName, RunFlag runFlag) {
        return pool().run(task, taskName, runFlag);
    }

    public static <T> CompletableFuture<T> run(Func<T> task) {
        return pool().run(task);
    }

    public static <T> CompletableFuture<T> run(Func<T> task, String taskName, RunFlag runFlag) {
        return pool().run(task, taskName, runFlag);
    }

    public static Tuple<CompletableFuture<Void>, CompletableFuture<Void>[]> anyOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }
        //Lambda method ref -> 对select的引用不明确
        CompletableFuture<Void>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of((CompletableFuture) CompletableFuture.anyOf(futures), futures);
    }

    private static <T1, T2> Tuple<CompletableFuture<T1>, CompletableFuture<T2>[]> nullReturn() {
        return Tuple.of(CompletableFuture.completedFuture(null), new CompletableFuture[0]);
    }

    public static <T> Tuple<CompletableFuture<T>, CompletableFuture<T>[]> anyOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<T>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public static Tuple<CompletableFuture<Void>, CompletableFuture<Void>[]> allOf(Action... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<Void>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of(CompletableFuture.allOf(futures), futures);
    }

    public static <T> Tuple<CompletableFuture<Void>, CompletableFuture<T>[]> allOf(Func<T>... tasks) {
        if (Arrays.isEmpty(tasks)) {
            return nullReturn();
        }

        CompletableFuture<T>[] futures = NQuery.of(tasks).select(p -> run(p)).toArray();
        return Tuple.of(CompletableFuture.allOf(futures), futures);
    }

    public static ScheduledFuture<?> scheduleOnce(@NonNull Action task, @NonNull Date time) {
        long initDelay = time.getTime() - System.currentTimeMillis();
        return scheduleOnce(task, initDelay);
    }

    public static ScheduledFuture<?> scheduleOnce(@NonNull Action task, long delay) {
        return scheduler.schedule((Runnable) wrap(task), delay, TimeUnit.MILLISECONDS);
    }

    public static ScheduledFuture<?> scheduleUntil(@NonNull Action task, @NonNull Func<Boolean> preCheckFunc, long delay) {
        $<ScheduledFuture<?>> future = $();
        future.v = schedule(() -> {
            if (preCheckFunc.invoke()) {
                future.v.cancel(true);
                return;
            }
            task.invoke();
        }, delay);
        return future.v;
    }

    public static List<? extends ScheduledFuture<?>> scheduleDaily(Action task, String... timeArray) {
        return NQuery.of(timeArray).select(p -> scheduleDaily(task, Time.valueOf(p))).toList();
    }

    /**
     * 每天按指定时间执行
     *
     * @param task Action
     * @param time "HH:mm:ss"
     * @return Future
     */
    public static ScheduledFuture<?> scheduleDaily(@NonNull Action task, @NonNull Time time) {
        long oneDay = 24 * 60 * 60 * 1000;
        long initDelay = DateTime.valueOf(DateTime.now().toDateString() + " " + time).getTime() - System.currentTimeMillis();
        initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;

        return schedule(task, initDelay, oneDay);
    }

    public static ScheduledFuture<?> schedule(Action task, long delay) {
        return schedule(task, delay, delay);
    }

    public static ScheduledFuture<?> schedule(@NonNull Action task, long initialDelay, long delay) {
        return scheduler.scheduleWithFixedDelay(wrap(task), initialDelay, delay, TimeUnit.MILLISECONDS);
    }

    static TaskScheduler.Task<?> wrap(Action task) {
        //schedule 抛出异常会终止
        return new TaskScheduler.Task<>(null, null, () -> App.quietly(task));
    }
}
