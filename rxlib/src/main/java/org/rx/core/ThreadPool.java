package org.rx.core;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.InternalThreadLocalMap;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.*;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.require;

@SuppressWarnings(NON_UNCHECKED)
@Slf4j
public class ThreadPool extends ThreadPoolExecutor {
    @RequiredArgsConstructor
    @Getter
    public static class MultiTaskFuture<T, TS> {
        final CompletableFuture<T> future;
        final CompletableFuture<TS>[] subFutures;
    }

    @RequiredArgsConstructor
    public static class ThreadQueue extends LinkedTransferQueue<Runnable> {
        private static final long serialVersionUID = 4283369202482437480L;
        private ThreadPool pool;
        final int queueCapacity;
        final AtomicInteger counter = new AtomicInteger();

        public boolean isFullLoad() {
            return counter.get() >= queueCapacity;
        }

        @Override
        public boolean isEmpty() {
            return counter.get() == 0;
        }

        @Override
        public int size() {
            return counter.get();
        }

        @SneakyThrows
        @Override
        public boolean offer(Runnable r) {
            if (isFullLoad()) {
                boolean logged = false;
                while (isFullLoad()) {
                    if (!logged) {
                        log.warn("Block caller thread until queue[{}/{}] polled then offer {}", counter.get(), queueCapacity, r);
                        logged = true;
                    }
                    synchronized (this) {
                        wait(500);
                    }
                }
                log.debug("Wait poll ok");
            }
            counter.incrementAndGet();
            Task<?> task = pool.setTask(r);
            if (task != null && task.flags.has(RunFlag.TRANSFER)) {
                super.transfer(r);
                return true;
            }
            return super.offer(r);
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            boolean ok = true;
            try {
                Runnable r = super.poll(timeout, unit);
                ok = r != null;
                return r;
            } catch (InterruptedException e) {
                ok = false;
                throw e;
            } finally {
                if (ok) {
                    log.debug("Notify poll");
                    doNotify();
                }
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            try {
                return super.take();
            } finally {
                log.debug("Notify take");
                doNotify();
            }
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = super.remove(o);
            if (ok) {
                log.debug("Notify remove");
                doNotify();
            }
            return ok;
        }

        private void doNotify() {
            int c = counter.decrementAndGet();
            synchronized (this) {
                if (c < 0) {
                    counter.set(super.size());
                    TraceHandler.INSTANCE.saveMetric(Constants.MetricName.THREAD_QUEUE_SIZE_ERROR.name(),
                            String.format("FIX SIZE %s -> %s", c, counter));
                }
                notify();
            }
        }
    }

    static class Task<T> implements Runnable, Callable<T>, Supplier<T> {
        static <T> Task<T> adapt(Callable<T> fn) {
            Task<T> t = as(fn);
            return t != null ? t : new Task<>(fn::call, null, null);
        }

        static <T> Task<T> adapt(Runnable fn) {
            Task<T> t = as(fn);
            return t != null ? t : new Task<>(() -> {
                fn.run();
                return null;
            }, null, null);
        }

        static <T> Task<T> as(Object fn) {
            return fn instanceof Task ? (Task<T>) fn : null;
        }

        final Func<T> fn;
        final FlagsEnum<RunFlag> flags;
        final Object id;
        final InternalThreadLocalMap parent;
        final String traceId;

        Task(Func<T> fn, FlagsEnum<RunFlag> flags, Object id) {
            if (flags == null) {
                flags = RunFlag.NONE.flags();
            }
            if (RxConfig.INSTANCE.threadPool.traceName != null) {
                flags.add(RunFlag.THREAD_TRACE);
            }

            this.fn = fn;
            this.flags = flags;
            this.id = id;
            parent = flags.has(RunFlag.INHERIT_FAST_THREAD_LOCALS) ? InternalThreadLocalMap.getIfSet() : null;
            traceId = CTX_TRACE_ID.get();
        }

        @SneakyThrows
        @Override
        public T call() {
            try {
                return fn.invoke();
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log(toString(), e);
                throw e;
            }
        }

        @Override
        public void run() {
            call();
        }

        @Override
        public T get() {
            return call();
        }

        @Override
        public String toString() {
            String hc = id != null ? id.toString() : Integer.toHexString(hashCode());
            return String.format("Task-%s[%s]", hc, flags.getValue());
        }
    }

    static class FutureTaskAdapter<T> extends FutureTask<T> {
        final Task<T> task;

        public FutureTaskAdapter(Callable<T> callable) {
            super(callable);
            task = Task.as(callable);
        }

        public FutureTaskAdapter(Runnable runnable, T result) {
            super(runnable, result);
            task = Task.as(runnable);
        }
    }

    //region static members
    public static volatile Func<String> traceIdGenerator;
    public static final Delegate<EventPublisher.StaticEventPublisher, NEventArgs<String>> onTraceIdChanged = Delegate.create();
    static final ThreadLocal<Queue<String>> CTX_PARENT_TRACE_ID = new InheritableThreadLocal<>();
    static final ThreadLocal<String> CTX_TRACE_ID = new InheritableThreadLocal<>();
    static final FastThreadLocal<Boolean> ASYNC_CONTINUE = new FastThreadLocal<>();
    static final FastThreadLocal<Object> COMPLETION_RETURNED_VALUE = new FastThreadLocal<>();
    static final String POOL_NAME_PREFIX = "℞Threads-";
    static final IntWaterMark DEFAULT_CPU_WATER_MARK = new IntWaterMark(RxConfig.INSTANCE.threadPool.lowCpuWaterMark,
            RxConfig.INSTANCE.threadPool.highCpuWaterMark);
    static final Map<Object, RefCounter<ReentrantLock>> taskLockMap = new ConcurrentHashMap<>(8);
    static final Map<Object, CompletableFuture<?>> taskSerialMap = new ConcurrentHashMap<>();

    public static String startTrace(String traceId) {
        return startTrace(traceId, false);
    }

    @SneakyThrows
    public static String startTrace(String traceId, boolean requiresNew) {
        String tid = CTX_TRACE_ID.get();
        if (tid == null) {
            tid = traceId != null ? traceId :
                    traceIdGenerator != null ? traceIdGenerator.invoke() : ULID.randomULID().toBase64String();
            CTX_TRACE_ID.set(tid);
        } else if (traceId != null && !traceId.equals(tid)) {
            if (!requiresNew) {
                log.warn("The traceId already mapped to {} and can not set to {}", tid, traceId);
            } else {
                LinkedList<String> queue = (LinkedList<String>) CTX_PARENT_TRACE_ID.get();
                if (queue == null) {
                    CTX_PARENT_TRACE_ID.set(queue = new LinkedList<>());
                }
                if (queue.size() > RxConfig.INSTANCE.threadPool.maxTraceDepth) {
                    queue.poll();
                }
                queue.addFirst(tid);
                CTX_TRACE_ID.set(traceId);
                log.info("trace requires new to {} with parent {}", traceId, tid);
                tid = traceId;
            }
        }
//        log.info("trace start {}", tid);
        onTraceIdChanged.invoke(EventPublisher.STATIC_EVENT_INSTANCE, new NEventArgs<>(tid));
        return tid;
    }

    public static String traceId() {
        return CTX_TRACE_ID.get();
    }

    @SneakyThrows
    public static void endTrace() {
//        log.info("trace end");
        Queue<String> queue = CTX_PARENT_TRACE_ID.get();
        String parentTid;
        if (queue != null && (parentTid = queue.poll()) != null) {
            CTX_TRACE_ID.set(parentTid);
            if (queue.isEmpty()) {
                CTX_PARENT_TRACE_ID.remove();
            }
        } else {
            parentTid = null;
            CTX_TRACE_ID.remove();
        }
        onTraceIdChanged.invoke(EventPublisher.STATIC_EVENT_INSTANCE, new NEventArgs<>(parentTid));
    }

    public static <T> T completionReturnedValue() {
        return (T) COMPLETION_RETURNED_VALUE.getIfExists();
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(Constants.CPU_THREADS, Math.floor(Constants.CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    static ThreadFactory newThreadFactory(String name) {
        return new DefaultThreadFactory(String.format("%s%s", POOL_NAME_PREFIX, name), true
//                , Thread.NORM_PRIORITY + 1
        );
    }

    static boolean asyncContinueFlag(boolean def) {
        Boolean ac = ASYNC_CONTINUE.getIfExists();
        ASYNC_CONTINUE.remove();
        if (ac == null) {
            return def;
        }
        return ac;
    }
    //endregion

    //region instance members
    @Getter
    final String poolName;
    final Map<Runnable, Task<?>> taskMap = new ConcurrentHashMap<>();
    //runAsync() wrap task to AsynchronousCompletionTask, and this::execute adapt function will not work
    final Executor asyncExecutor = super::execute;

    @Override
    public void setMaximumPoolSize(int maximumPoolSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        throw new UnsupportedOperationException();
    }

    public ThreadPool(String poolName) {
        //computeThreads(1, 2, 1)
        this(RxConfig.INSTANCE.threadPool.initSize, RxConfig.INSTANCE.threadPool.queueCapacity, poolName);
    }

    public ThreadPool(int initSize, int queueCapacity, String poolName) {
        this(initSize, queueCapacity, DEFAULT_CPU_WATER_MARK, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param initSize      最小线程数
     * @param queueCapacity LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int initSize, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        super(checkSize(initSize), Integer.MAX_VALUE,
                RxConfig.INSTANCE.threadPool.keepAliveSeconds, TimeUnit.SECONDS, new ThreadQueue(checkCapacity(queueCapacity)), newThreadFactory(poolName), (r, executor) -> {
                    if (executor.isShutdown()) {
                        log.warn("ThreadPool {} is shutdown", poolName);
                        return;
                    }
                    executor.getQueue().offer(r);
                });
        super.allowCoreThreadTimeOut(true);
        ((ThreadQueue) super.getQueue()).pool = this;
        this.poolName = poolName;

        setDynamicSize(cpuWaterMark);
    }

    private static int checkSize(int size) {
        if (size <= 0) {
            size = Constants.CPU_THREADS + 1;
        }
        return size;
    }

    private static int checkCapacity(int capacity) {
        if (capacity <= 0) {
            //todo set with memorysize
            capacity = Constants.CPU_THREADS * 64;
        }
        return capacity;
    }

    public void setDynamicSize(IntWaterMark cpuWaterMark) {
        if (cpuWaterMark.getLow() < 0) {
            cpuWaterMark.setLow(0);
        }
        if (cpuWaterMark.getHigh() > 100) {
            cpuWaterMark.setHigh(100);
        }
        CpuWatchman.INSTANCE.register(this, cpuWaterMark);
    }
    //endregion

    //region v1
    @Override
    public void execute(Runnable command) {
        super.execute(Task.adapt(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        RunnableFuture<Void> ft = newTaskFor(task, null);
        super.execute(ft);
        return ft;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        RunnableFuture<T> ft = newTaskFor(task, result);
        super.execute(ft);
        return ft;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        RunnableFuture<T> ft = newTaskFor(task);
        super.execute(ft);
        return ft;
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTaskAdapter<>(Task.adapt(runnable), value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTaskAdapter<>(Task.adapt(callable));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return super.invokeAny(Linq.from(tasks).select(p -> Task.adapt(p)).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return super.invokeAny(Linq.from(tasks).select(p -> Task.adapt(p)).toList(), timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return super.invokeAll(Linq.from(tasks).select(p -> Task.adapt(p)).toList());
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return super.invokeAll(Linq.from(tasks).select(p -> Task.adapt(p)).toList(), timeout, unit);
    }

    public Future<Void> run(Action task) {
        return run(task, null, null);
    }

    public Future<Void> run(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        return submit((Callable<Void>) new Task<Void>(task.toFunc(), flags, taskId));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null, null);
    }

    public <T> Future<T> run(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return submit((Callable<T>) new Task<>(task, flags, taskId));
    }

    @SneakyThrows
    public <T> T runAny(Collection<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) new Task<>(p, null, null)).toList();
        return timeoutMillis > 0 ? super.invokeAny(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAny(callables);
    }

    @SneakyThrows
    public <T> List<Future<T>> runAll(Collection<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) new Task<>(p, null, null)).toList();
        return timeoutMillis > 0 ? super.invokeAll(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAll(callables);
    }

    public <T> CompletionService<T> newCompletionService() {
        return new ExecutorCompletionService<>(this);
    }
    //endregion

    //region v2
    public CompletableFuture<Void> runAsync(Action task) {
        return runAsync(task, null, null);
    }

    public CompletableFuture<Void> runAsync(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<Void> t = new Task<>(task.toFunc(), flags, taskId);
        return CompletableFuture.runAsync(t, asyncExecutor);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<T> t = new Task<>(task, flags, taskId);
        return CompletableFuture.supplyAsync(t, asyncExecutor);
    }

    public <T> Future<T> runSerial(Func<T> task, Object taskId) {
        return runSerial(task, taskId, null);
    }

    public <T> Future<T> runSerial(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return runSerialAsync(task, taskId, flags, true);
    }

    public <T> CompletableFuture<T> runSerialAsync(Func<T> task, Object taskId) {
        return runSerialAsync(task, taskId, null);
    }

    public <T> CompletableFuture<T> runSerialAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        return runSerialAsync(task, taskId, flags, false);
    }

    <T> CompletableFuture<T> runSerialAsync(@NonNull Func<T> task, @NonNull Object taskId, FlagsEnum<RunFlag> flags, boolean reuse) {
        Function<Object, CompletableFuture<T>> mfn = k -> {
            Task<T> t = new Task<>(task, flags, taskId);
            return CompletableFuture.supplyAsync(t, asyncExecutor).whenCompleteAsync((r, e) -> taskSerialMap.remove(taskId));
        };
        CompletableFuture<T> v, newValue = null;
        CompletableFuture<T> f = ((v = (CompletableFuture<T>) taskSerialMap.get(taskId)) == null &&
                (newValue = mfn.apply(taskId)) != null &&
                (v = (CompletableFuture<T>) taskSerialMap.putIfAbsent(taskId, newValue)) == null) ? newValue : v;

        if (newValue == null) {
            f = f.thenApplyAsync(t -> {
                COMPLETION_RETURNED_VALUE.set(t);
                try {
                    return task.get();
                } finally {
                    COMPLETION_RETURNED_VALUE.remove();
                }
            }, this);
            if (!reuse) {
                taskSerialMap.put(taskId, f);
            }
        }
        return f;
    }

    public <T> MultiTaskFuture<T, T> runAnyAsync(Collection<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = new Task<>(task, null, null);
            return CompletableFuture.supplyAsync(t, asyncExecutor);
        }).toArray();
        return new MultiTaskFuture<>((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public <T> MultiTaskFuture<Void, T> runAllAsync(Collection<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = new Task<>(task, null, null);
            //allOf().join() will hang
//            return wrap(CompletableFuture.supplyAsync(t, this), t.traceId);
            return CompletableFuture.supplyAsync(t, asyncExecutor);
        }).toArray();
        return new MultiTaskFuture<>(CompletableFuture.allOf(futures), futures);
    }
    //endregion

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        Task<?> task = setTask(r);
        if (task == null) {
            return;
        }

        FlagsEnum<RunFlag> flags = task.flags;
        if (flags.has(RunFlag.SINGLE)) {
            RefCounter<ReentrantLock> ctx = getContextForLock(task.id);
            if (!ctx.ref.tryLock()) {
                throw new InterruptedException(String.format("SingleScope %s locked by other thread", task.id));
            }
            ctx.incrementRefCnt();
            log.debug("CTX tryLock {} -> {}", task.id, flags.name());
        } else if (flags.has(RunFlag.SYNCHRONIZED)) {
            RefCounter<ReentrantLock> ctx = getContextForLock(task.id);
            ctx.incrementRefCnt();
            ctx.ref.lock();
            log.debug("CTX lock {} -> {}", task.id, flags.name());
        }
        if (flags.has(RunFlag.PRIORITY) && !getQueue().isEmpty()) {
            CpuWatchman.incrSize(this);
        }
        //TransmittableThreadLocal
        if (task.parent != null) {
            setThreadLocalMap(t, task.parent);
        }
        if (flags.has(RunFlag.THREAD_TRACE)) {
            startTrace(task.traceId);
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Task<?> task = getTask(r, true);
        //Default Behavior with Callable
        //The uncaught exception - if one occurs - is considered as a part of this Future.
        //Thus the JDK doesn't try to notify the handler.
//      if (t == null && r instanceof FutureTask) {
//          try {
//              FutureTask<?> f = (FutureTask<?>) r;
//              if (f.isDone()) {
//                  f.get();
//              }
//          } catch (CancellationException ce) {
//              t = ce;
//          } catch (ExecutionException ee) {
//              t = ee.getCause();
//          } catch (InterruptedException ie) {
//              Thread.currentThread().interrupt();
//          }
//          if (t != null) {
//              TraceHandler.INSTANCE.log(t);
//          }
//      }
        if (task == null) {
            return;
        }

        FlagsEnum<RunFlag> flags = task.flags;
        Object id = task.id;
        if (id != null) {
            RefCounter<ReentrantLock> ctx = taskLockMap.get(id);
            if (ctx != null) {
                boolean doRemove = false;
                if (ctx.decrementRefCnt() <= 0) {
                    taskLockMap.remove(id);
                    doRemove = true;
                }
                log.debug("CTX unlock{} {} -> {}", doRemove ? " & clear" : "", id, task.flags.name());
                ctx.ref.unlock();
            }
        }
        if (task.parent != null) {
            setThreadLocalMap(Thread.currentThread(), null);
        }
        if (flags.has(RunFlag.THREAD_TRACE)) {
            endTrace();
        }
    }

    private void setThreadLocalMap(Thread t, InternalThreadLocalMap threadLocalMap) {
        if (t instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) t).setThreadLocalMap(threadLocalMap);
            return;
        }

        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = Reflects.readStaticField(InternalThreadLocalMap.class, "slowThreadLocalMap");
        if (threadLocalMap == null) {
            slowThreadLocalMap.remove();
            return;
        }
        slowThreadLocalMap.set(threadLocalMap);
    }

    private RefCounter<ReentrantLock> getContextForLock(Object id) {
        if (id == null) {
            throw new InvalidException("SINGLE or SYNCHRONIZED flag require a taskId");
        }

        return taskLockMap.computeIfAbsent(id, k -> new RefCounter<>(new ReentrantLock()));
    }

    private Task<?> setTask(Runnable r) {
        Task<?> task = taskMap.get(r);
        if (task == null) {
            if (r instanceof FutureTaskAdapter) {
                task = ((FutureTaskAdapter<?>) r).task;
            } else if (r instanceof CompletableFuture.AsynchronousCompletionTask) {
                task = Task.as(Reflects.readField(r, "fn"));
            } else {
                task = Task.as(r);
            }
            if (task != null) {
                taskMap.put(r, task);
            }
        }
        return task;
    }

    private Task<?> getTask(Runnable r, boolean remove) {
        return remove ? taskMap.remove(r) : taskMap.get(r);
    }

    @Override
    public String toString() {
        return String.format("%s%s@%s", POOL_NAME_PREFIX, poolName, Integer.toHexString(hashCode()));
    }
}
