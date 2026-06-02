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
import org.apache.commons.lang3.reflect.FieldUtils;
import org.rx.bean.*;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

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

    public static class ThreadQueue extends LinkedTransferQueue<Runnable> {
        private static final long serialVersionUID = 4283369202482437480L;
        private ThreadPool pool;
        final int queueCapacity;
        final AtomicInteger counter = new AtomicInteger();
        final Semaphore availableSlots;
        final LongAdder offerBlockCount = new LongAdder();
        final LongAdder offerBlockMillis = new LongAdder();
        final AtomicLong offerBlockMaxMillis = new AtomicLong();
        final LongAdder offerRejectedCount = new LongAdder();
        final LongAdder offerCallerRunsCount = new LongAdder();

        public ThreadQueue(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            this.availableSlots = new Semaphore(queueCapacity, false);
        }

        static final class CapacitySnapshot {
            final int capacity;
            final int counter;
            final int remaining;
            final int slots;

            CapacitySnapshot(int capacity, int counter, int remaining, int slots) {
                this.capacity = capacity;
                this.counter = counter;
                this.remaining = remaining;
                this.slots = slots;
            }
        }

        CapacitySnapshot capacitySnapshot() {
            int c = counter.get();
            return new CapacitySnapshot(queueCapacity, c, Math.max(0, queueCapacity - c), availableSlots.availablePermits());
        }

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

        @Override
        public boolean offer(Runnable r) {
            int acquireResult = acquireSlot(r);
            if (acquireResult == 0) {
                return true;
            }
            if (acquireResult < 0) {
                if (pool != null) {
                    pool.rejectTask(r);
                }
                throw new RejectedExecutionException("ThreadPool " + poolName() + " queue offer rejected");
            }

            boolean offered = false;
            counter.incrementAndGet();
            try {
                Task<?> task = pool != null ? pool.setTask(r) : null;
                if (task != null && task.flags.has(RunFlag.TRANSFER)) {
                    super.transfer(r);
                    offered = true;
                    return true;
                }
                offered = super.offer(r);
                return offered;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("ThreadPool " + poolName() + " queue offer interrupted", e);
            } finally {
                if (!offered) {
                    if (pool != null) {
                        pool.getTask(r, true);
                    }
                    doNotify();
                }
            }
        }

        @Override
        public int remainingCapacity() {
            return Math.max(0, queueCapacity - counter.get());
        }

        private int acquireSlot(Runnable r) {
            if (availableSlots.tryAcquire()) {
                return 1;
            }

            RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
            ThreadPoolQueueOfferMode mode = conf.queueOfferMode != null ? conf.queueOfferMode : ThreadPoolQueueOfferMode.BLOCK;
            long timeoutMillis = Math.max(0L, conf.queueOfferTimeoutMillis);
            long startNanos = System.nanoTime();
            if (mode == ThreadPoolQueueOfferMode.BLOCK) {
                return blockUntilSlot(r, startNanos);
            }
            if (timeoutMillis > 0L && waitForSlot(r, timeoutMillis, startNanos)) {
                return 1;
            }

            recordOfferBlock(startNanos);
            if (mode == ThreadPoolQueueOfferMode.CALLER_RUNS) {
                offerCallerRunsCount.increment();
                if (log.isWarnEnabled()) {
                    log.warn("Run task in caller thread because queue[{}/{}] is full, pool={}, task={}",
                            counter.get(), queueCapacity, poolName(), r);
                }
                if (pool != null) {
                    pool.runInCaller(r);
                } else {
                    r.run();
                }
                return 0;
            }

            offerRejectedCount.increment();
            if (log.isWarnEnabled()) {
                log.warn("Reject task because queue[{}/{}] is full after {}ms, pool={}, task={}",
                        counter.get(), queueCapacity, timeoutMillis, poolName(), r);
            }
            return -1;
        }

        private int blockUntilSlot(Runnable r, long startNanos) {
            boolean logged = false;
            for (;;) {
                if (isPoolShutdown()) {
                    offerRejectedCount.increment();
                    return -1;
                }
                try {
                    if (availableSlots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        if (isPoolShutdown()) {
                            availableSlots.release();
                            offerRejectedCount.increment();
                            return -1;
                        }
                        recordOfferBlock(startNanos);
                        if (logged && log.isDebugEnabled()) {
                            log.debug("Wait poll ok");
                        }
                        return 1;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    offerRejectedCount.increment();
                    return -1;
                }
                if (!logged) {
                    log.warn("Block caller thread until queue[{}/{}] polled then offer {}", counter.get(), queueCapacity, r);
                    logged = true;
                }
            }
        }

        private boolean waitForSlot(Runnable r, long timeoutMillis, long startNanos) {
            boolean logged = false;
            long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            for (;;) {
                if (isPoolShutdown()) {
                    offerRejectedCount.increment();
                    return false;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                long waitNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(500L));
                try {
                    if (availableSlots.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                        if (isPoolShutdown()) {
                            availableSlots.release();
                            offerRejectedCount.increment();
                            return false;
                        }
                        recordOfferBlock(startNanos);
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    offerRejectedCount.increment();
                    return false;
                }
                if (!logged) {
                    log.warn("Block caller thread up to {}ms until queue[{}/{}] polled then offer {}",
                            timeoutMillis, counter.get(), queueCapacity, r);
                    logged = true;
                }
            }
        }

        private boolean isPoolShutdown() {
            return pool != null && pool.isShutdown();
        }

        private void recordOfferBlock(long startNanos) {
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            offerBlockCount.increment();
            offerBlockMillis.add(millis);
            long old;
            while (millis > (old = offerBlockMaxMillis.get()) && !offerBlockMaxMillis.compareAndSet(old, millis)) {
                // retry
            }
        }

        private String poolName() {
            return pool == null ? "unknown" : pool.poolName;
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
                    if (log.isDebugEnabled()) {
                        log.debug("Notify poll");
                    }
                    doNotify();
                }
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            boolean ok = true;
            try {
                return super.take();
            } catch (InterruptedException e) {
                ok = false;
                throw e;
            } finally {
                if (ok) {
                    if (log.isDebugEnabled()) {
                        log.debug("Notify take");
                    }
                    doNotify();
                }
            }
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = super.remove(o);
            if (ok) {
                cleanupRemoved(o);
                if (log.isDebugEnabled()) {
                    log.debug("Notify remove");
                }
                doNotify();
            }
            return ok;
        }

        @Override
        public Runnable poll() {
            Runnable r = super.poll();
            if (r != null) {
                cleanupRemoved(r);
                doNotify();
            }
            return r;
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            return drainTo(c, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            Objects.requireNonNull(c, "collection");
            if (c == this) {
                throw new IllegalArgumentException("Can not drain to self");
            }
            if (maxElements <= 0) {
                return 0;
            }

            int n = 0;
            while (n < maxElements) {
                Runnable r = super.poll();
                if (r == null) {
                    break;
                }
                try {
                    c.add(r);
                    n++;
                } finally {
                    cleanupRemoved(r);
                    doNotify();
                }
            }
            return n;
        }

        @Override
        public void clear() {
            for (;;) {
                Runnable r = super.poll();
                if (r == null) {
                    return;
                }
                cleanupRemoved(r);
                doNotify();
            }
        }

        private void cleanupRemoved(Object o) {
            if (pool != null && o instanceof Runnable) {
                pool.getTask((Runnable) o, true);
            }
        }

        private void doNotify() {
            int c = counter.getAndUpdate(v -> Math.max(0, v - 1));
            if (c > 0) {
                availableSlots.release();
            } else if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record(Constants.MetricName.THREAD_QUEUE_SIZE_ERROR.name(), 1D, "message=FIX SIZE < 0");
            }
        }
    }

    public static class Task<T> implements Runnable, Callable<T>, Supplier<T> {
        private static final AtomicInteger TASK_COUNTER = new AtomicInteger();
        // 减少stackTrace
        public static Task<Void> adapt(Runnable fn) {
            return Task.<Void>adapt(fn, null, null);
        }

        public static <T> Task<T> adapt(Callable<T> fn) {
            return adapt(fn, null, null);
        }

        static <T> Task<T> adapt(Callable<T> fn, FlagsEnum<RunFlag> flags, Object id) {
            Task<T> t = as(fn);
            if (t != null) {
                if (id == null && flags == null) {
                    return t;
                }
                if (t.id == id) {
                    return t;
                }
                // Inherit from existing task if not provided
                if (id == null) {
                    id = t.id;
                }
                if (flags == null) {
                    flags = t.flags;
                } else if (t.flags != null) {
                    flags.add(t.flags);
                }
            }
            return new Task<>(fn, flags, id);
        }

        static <T> Task<T> adapt(Runnable fn, FlagsEnum<RunFlag> flags, Object id) {
            Task<T> t = as(fn);
            if (t != null) {
                if (id == null && flags == null) {
                    return t;
                }
                if (t.id == id) {
                    return t;
                }
                // Inherit from existing task if not provided
                if (id == null) {
                    id = t.id;
                }
                if (flags == null) {
                    flags = t.flags;
                } else if (t.flags != null) {
                    flags.add(t.flags);
                }
            }
            return new Task<>(() -> {
                fn.run();
                return null;
            }, flags, id);
        }

        static <T> Task<T> as(Object fn) {
            return fn instanceof Task ? (Task<T>) fn : null;
        }

        final Callable<T> fn;
        final FlagsEnum<RunFlag> flags;
        final Object id;
        final InternalThreadLocalMap parent;
        final String traceId;
        final StackTraceElement[] stackTrace;
        volatile boolean skipExecution;
        volatile boolean singleLockAcquired;
        volatile boolean threadLocalMapSet;
        volatile boolean threadTraceStarted;
        volatile boolean poolManaged;
        volatile boolean callerRuns;
        volatile InternalThreadLocalMap oldThreadLocalMap;

        private Task(Callable<T> fn, FlagsEnum<RunFlag> flags, Object id) {
            if (flags == null) {
                flags = RunFlag.NONE.flags();
            }
            RxConfig conf = RxConfig.INSTANCE;
            if (conf.threadPool.traceName != null) {
                flags.add(RunFlag.THREAD_TRACE);
            }
            Object ctxST = CTX_STACK_TRACE.getIfExists();
            if (ctxST != null) {
                if (ctxST instanceof StackTraceElement[]) {
                    stackTrace = (StackTraceElement[]) ctxST;
                } else {
                    stackTrace = null;
                }
            } else if (conf.trace.slowMethodElapsedMicros > 0) {
                int threshold = conf.threadPool.slowMethodSamplingPercent;
                if (threshold > 0 && ((TASK_COUNTER.incrementAndGet() & Integer.MAX_VALUE) % 100) < threshold) {
                    stackTrace = new Throwable().getStackTrace();
                } else {
                    stackTrace = null;
                }
            } else {
                stackTrace = null;
            }

            this.fn = fn;
            this.flags = flags;
            this.id = id;
            parent = flags.has(RunFlag.INHERIT_FAST_THREAD_LOCALS) ? InternalThreadLocalMap.getIfSet() : null;
            traceId = traceId();
        }

        public static void runWithTrace(String traceId, Runnable task) {
            clearTrace();
            try {
                if (Strings.isNotEmpty(traceId)) {
                    startTrace(traceId);
                }
                task.run();
            } finally {
                clearTrace();
            }
        }

        public static <T> T callWithTrace(String traceId, Callable<T> task) throws Exception {
            clearTrace();
            try {
                if (Strings.isNotEmpty(traceId)) {
                    startTrace(traceId);
                }
                return task.call();
            } finally {
                clearTrace();
            }
        }

        @SneakyThrows
        @Override
        public T call() {
            if (skipExecution) {
                return null;
            }
            boolean cleanupTrace = false;
            if (!poolManaged) {
                clearTrace();
                cleanupTrace = true;
                if (Strings.isNotEmpty(traceId)) {
                    startTrace(traceId);
                }
            }
            try {
                return callCore();
            } finally {
                if (cleanupTrace) {
                    clearTrace();
                }
            }
        }

        @SneakyThrows
        private T callCore() {
            if (RxConfig.INSTANCE.trace.slowMethodElapsedMicros > 0) {
                T r = null;
                Throwable ex = null;
                long s = System.nanoTime();
                try {
                    r = fn.call();
                } catch (Throwable e) {
                    log.error(toString(), ex = e);
                    throw e;
                } finally {
                    Thread t = Thread.currentThread();
                    TraceHandler.INSTANCE.saveMethodTrace(t,
                            this.getClass().getSimpleName(), // fn.getClass().getSimpleName(),
                            stackTrace != null
                                    ? "[" + Linq.from(stackTrace).select(StackTraceElement::toString).toJoinString(Constants.STACK_TRACE_FLAG) + "]"
                                    : "Unknown",
                            id == null ? null : new Object[]{id},
                            r, ex, System.nanoTime() - s);
                }
                return r;
            }

            try {
                return fn.call();
            } catch (Throwable e) {
                log.error(toString(), e);
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
            return "Task-" + hc + "[" + flags.getValue() + "]";
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

    // region static members
    public static volatile Func<String> traceIdGenerator;
    public static final Delegate<EventPublisher.StaticEventPublisher, String> onTraceIdChanged = Delegate.create();
    static final ThreadLocal<LinkedList<Object>> CTX_TRACE_ID = new InheritableThreadLocal<LinkedList<Object>>() {
        @Override
        protected LinkedList<Object> initialValue() {
            return new LinkedList<>();
        }

        @Override
        protected LinkedList<Object> childValue(LinkedList<Object> parentValue) {
            // Thread.currentThread()是parent线程
            LinkedList<Object> c = new LinkedList<>();
            Object peek = parentValue.peek();
            if (peek != null) {
                String tid = peek instanceof Tuple ? ((Tuple<String, Integer>) peek).left : (String) peek;
                // log.debug("inherit {}", tid);
                c.add(Tuple.of(tid, 0));
            }
            return c;
        }
    };
    static final FastThreadLocal<Object> CTX_STACK_TRACE = new FastThreadLocal<>();
    static final FastThreadLocal<Boolean> CONTINUE_FLAG = new FastThreadLocal<>();
    private static final FastThreadLocal<Object> COMPLETION_RETURNED_VALUE = new FastThreadLocal<>();
    /**
     * 不可经 {@link Reflects#getFieldMap}：其字段缓存走 Caffeine，会在 Tasks 完成 createPool 前回调 {@link Tasks#executor()}。
     */
    @SuppressWarnings("unchecked")
    private static final ThreadLocal<InternalThreadLocalMap> SLOW_THREAD_LOCAL_MAP;
    private static final Constructor<InternalThreadLocalMap> INTERNAL_THREAD_LOCAL_MAP_CONSTRUCTOR;
    private static final Field INDEXED_VARIABLES_FIELD;

    static {
        try {
            SLOW_THREAD_LOCAL_MAP = (ThreadLocal<InternalThreadLocalMap>) FieldUtils.readStaticField(
                    InternalThreadLocalMap.class, "slowThreadLocalMap", true);
            INTERNAL_THREAD_LOCAL_MAP_CONSTRUCTOR = InternalThreadLocalMap.class.getDeclaredConstructor();
            INTERNAL_THREAD_LOCAL_MAP_CONSTRUCTOR.setAccessible(true);
            INDEXED_VARIABLES_FIELD = FieldUtils.getDeclaredField(InternalThreadLocalMap.class, "indexedVariables", true);
        } catch (IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final Map<Class<?>, Field> ASYNC_COMPLETION_FN_FIELDS = new ConcurrentHashMap<>();
    static final String POOL_NAME_PREFIX = "℞Threads-";
    static final Set<Object> runningSingleTasks = ConcurrentHashMap.newKeySet();
    static final Map<Object, CompletableFuture<?>> taskSerialMap = new ConcurrentHashMap<>();
    static final Map<Object, AtomicInteger> taskSerialCountMap = new ConcurrentHashMap<>();

    public static ThreadPool fixed(String poolName, int size, int queueCapacity) {
        int fixedSize = checkSize(size);
        return new ThreadPool(fixedSize, queueCapacity, null, poolName,
                fixedSize, fixedSize, 1, false);
    }

    public static String startTrace(String traceId) {
        return startTrace(traceId, false);
    }

    private static String newTraceId() {
        String tid = null;
        if (traceIdGenerator != null) {
            try {
                tid = traceIdGenerator.invoke();
            } catch (Throwable e) {
                log.error("startTrace", e);
            }
        }
        if (tid == null) {
            tid = ULID.randomULID().toBase64String();
        }
        return tid;
    }

    private static boolean traceDepthExceeded(LinkedList<Object> queue) {
        return queue.size() > RxConfig.INSTANCE.threadPool.maxTraceDepth;
    }

    @SneakyThrows
    public static String startTrace(String traceId, boolean requiresNew) {
        LinkedList<Object> queue = CTX_TRACE_ID.get();

        Object peek = queue.peek();
        Tuple<String, Integer> nestTid = null;
        String tid = peek instanceof Tuple ? (nestTid = (Tuple<String, Integer>) peek).left : (String) peek;
        byte f = 0;
        if (tid == null) {
            if (traceId != null) {
                tid = traceId;
            } else {
                tid = newTraceId();
            }
            queue.addFirst(tid);
            f = 1;
        } else {
            if (traceId != null && !traceId.equals(tid)) {
                if (!requiresNew) {
                    log.warn("RTrace - The traceId already mapped to {} and can not set to {}", peek, traceId);
                } else {
                    log.info("RTrace - Trace requires new to {} with parent {}", traceId, peek);
                    if (traceDepthExceeded(queue)) {
                        log.warn("RTrace - Discard traceId {}", traceId);
                    } else {
                        queue.addFirst(tid = traceId);
                    }
                    f = 3;
                }
            } else {
                if (traceDepthExceeded(queue)) {
                    log.warn("RTrace - Discard traceId {}", peek);
                } else {
                    queue.poll();
                    if (nestTid == null) {
                        nestTid = Tuple.of(tid, 1);
                    }
                    nestTid.right++;
                    queue.addFirst(nestTid);
                }
                f = 2;
            }
        }

        onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, tid);
        if (log.isDebugEnabled()) {
            switch (f) {
                case 1:
                    log.debug("RTrace - start new {}", queue);
                    break;
                case 2:
                    log.debug("RTrace - start nest {}", queue);
                    break;
                case 3:
                    log.debug("RTrace - start requires new {}", queue);
                    break;
            }
        }
        return tid;
    }

    @SneakyThrows
    static boolean startTaskTrace(String traceId) {
        LinkedList<Object> queue = CTX_TRACE_ID.get();
        Object peek = queue.peek();
        Tuple<String, Integer> nestTid = null;
        String current = peek instanceof Tuple ? (nestTid = (Tuple<String, Integer>) peek).left : (String) peek;
        String tid = traceId;
        byte f;

        if (current == null) {
            if (tid == null) {
                tid = newTraceId();
            }
            queue.addFirst(tid);
            f = 1;
        } else if (tid != null && tid.equals(current)) {
            if (traceDepthExceeded(queue)) {
                log.warn("RTrace - Discard traceId {}", peek);
                onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, current);
                return false;
            }
            queue.poll();
            if (nestTid == null) {
                nestTid = Tuple.of(current, 1);
            }
            nestTid.right++;
            queue.addFirst(nestTid);
            tid = current;
            f = 2;
        } else {
            if (tid == null) {
                tid = newTraceId();
            }
            log.info("RTrace - Trace requires new to {} with parent {}", tid, peek);
            if (traceDepthExceeded(queue)) {
                log.warn("RTrace - Discard traceId {}", tid);
                onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, current);
                return false;
            }
            queue.addFirst(tid);
            f = 3;
        }

        onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, tid);
        if (log.isDebugEnabled()) {
            switch (f) {
                case 1:
                    log.debug("RTrace - start new {}", queue);
                    break;
                case 2:
                    log.debug("RTrace - start nest {}", queue);
                    break;
                case 3:
                    log.debug("RTrace - start requires new {}", queue);
                    break;
            }
        }
        return true;
    }

    public static String traceId() {
        Object peek = CTX_TRACE_ID.get().peek();
        return peek instanceof Tuple ? ((Tuple<String, Integer>) peek).left : (String) peek;
    }

    @SneakyThrows
    public static void clearTrace() {
        CTX_TRACE_ID.remove();
        onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, null);
    }

    @SneakyThrows
    public static void endTrace() {
        LinkedList<Object> queue = CTX_TRACE_ID.get();
        if (queue.isEmpty()) {
            log.debug("RTrace - not started");
            return;
        }

        boolean next = false;
        Object peek = queue.peek();
        Tuple<String, Integer> nestTid = null;
        String tid = peek instanceof Tuple ? (nestTid = (Tuple<String, Integer>) peek).left : (String) peek;
        if (nestTid == null || --nestTid.right <= 0) {
            queue.poll();
            next = true;
        }
        log.debug("RTrace - end {} -> {}", queue, peek);

        while (next) {
            peek = queue.peek();
            nestTid = null;
            tid = peek instanceof Tuple ? (nestTid = (Tuple<String, Integer>) peek).left : (String) peek;
            if (nestTid != null && nestTid.right == 0) {
                queue.poll();
            } else {
                next = false;
            }
        }
        onTraceIdChanged.invoke(EventPublisher.STATIC_QUIETLY_EVENT_INSTANCE, tid);
    }

    public static <T> T completionReturnedValue() {
        return (T) COMPLETION_RETURNED_VALUE.getIfExists();
    }

    public static int computeThreads(double cpuUtilization, long waitTime, long cpuTime) {
        require(cpuUtilization, 0 <= cpuUtilization && cpuUtilization <= 1);

        return (int) Math.max(Constants.CPU_THREADS, Math.floor(Constants.CPU_THREADS * cpuUtilization * (1 + (double) waitTime / cpuTime)));
    }

    static ThreadFactory newThreadFactory(String name, int priority) {
        return new DefaultThreadFactory(String.format("%s%s", POOL_NAME_PREFIX, name), true, priority);
    }

    static boolean continueFlag(boolean def) {
        Boolean ac = CONTINUE_FLAG.getIfExists();
        CONTINUE_FLAG.remove();
        if (ac == null) {
            return def;
        }
        return ac;
    }
    // endregion

    // region instance members
    @Getter
    final String poolName;
    final int minIdleSize;
    final int maxPoolSize;
    final int resizeStep;
    final Map<Runnable, Task<?>> taskMap = new ConcurrentHashMap<>();
    final LongAdder taskRejectedCount = new LongAdder();
    final LongAdder serialRejectedCount = new LongAdder();
    final LongAdder singleSkipCount = new LongAdder();
    // runAsync() wrap task to AsynchronousCompletionTask, and this::execute adapt function will not work
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
        // computeThreads(1, 2, 1)
        this(RxConfig.INSTANCE.threadPool.initSize, RxConfig.INSTANCE.threadPool.queueCapacity, null, poolName);
    }

    /**
     * 当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
     *
     * @param initSize      最小线程数
     * @param queueCapacity LinkedTransferQueue 基于CAS的并发BlockingQueue的容量
     */
    public ThreadPool(int initSize, int queueCapacity, IntWaterMark cpuWaterMark, String poolName) {
        this(initSize, queueCapacity, cpuWaterMark, poolName, 0, 0, 0, true);
    }

    private ThreadPool(int initSize, int queueCapacity, IntWaterMark cpuWaterMark, String poolName,
            int minIdleSize, int maxPoolSize, int resizeStep, boolean allowCoreThreadTimeout) {
        super(checkSize(initSize), resolveMaxPoolSize(initSize, minIdleSize, maxPoolSize),
                RxConfig.INSTANCE.threadPool.keepAliveSeconds, TimeUnit.SECONDS,
                new ThreadQueue(checkCapacity(queueCapacity)), newThreadFactory(poolName, Thread.NORM_PRIORITY), (r, executor) -> {
                    ThreadPool pool = executor instanceof ThreadPool ? (ThreadPool) executor : null;
                    if (executor.isShutdown()) {
                        if (pool != null) {
                            pool.getTask(r, true);
                        }
                        log.warn("ThreadPool {} is shutdown", poolName);
                        throw new RejectedExecutionException("ThreadPool " + poolName + " is shutdown");
                    }
                    if (pool != null) {
                        pool.rejectTask(r);
                    }
                    throw new RejectedExecutionException("ThreadPool " + poolName + " rejected task");
                });
        super.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        ((ThreadQueue) super.getQueue()).pool = this;
        this.poolName = poolName;
        this.minIdleSize = resolveMinIdleSize(minIdleSize);
        this.maxPoolSize = resolveMaxPoolSize(initSize, minIdleSize, maxPoolSize);
        this.resizeStep = resolveResizeStep(resizeStep);

        dynamicSizeByCpuLoad(cpuWaterMark);
    }

    private static int checkSize(int size) {
        if (size <= 0) {
            size = Constants.CPU_THREADS + 1;
        }
        return size;
    }

    private static int checkCapacity(int capacity) {
        if (capacity <= 0) {
            // todo set with memorysize
            capacity = Constants.CPU_THREADS * 64;
        }
        return capacity;
    }

    private static int resolveMinIdleSize(int minIdleSize) {
        int configured = minIdleSize > 0 ? minIdleSize : RxConfig.INSTANCE.threadPool.minIdleSize;
        return Math.max(1, configured);
    }

    private static int resolveMaxPoolSize(int initSize, int minIdleSize, int maxPoolSize) {
        int configured = maxPoolSize > 0 ? maxPoolSize : RxConfig.INSTANCE.threadPool.maxPoolSize;
        return Math.max(checkSize(initSize), Math.max(resolveMinIdleSize(minIdleSize), configured));
    }

    private static int resolveResizeStep(int resizeStep) {
        int configured = resizeStep > 0 ? resizeStep : RxConfig.INSTANCE.threadPool.resizeStep;
        return Math.max(1, configured);
    }

    int minIdleSize() {
        return minIdleSize;
    }

    int maxPoolSize() {
        return maxPoolSize;
    }

    int resizeStep() {
        return resizeStep;
    }

    public void dynamicSizeByCpuLoad(IntWaterMark cpuWaterMark) {
        if (cpuWaterMark == null) {
            cpuWaterMark = RxConfig.INSTANCE.threadPool.cpuWaterMark;
        }
        CpuWatchman.INSTANCE.register(this, cpuWaterMark);
    }
    // endregion

    // region v1

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
        return new FutureTaskAdapter<>(Task.adapt(runnable, null, null), value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTaskAdapter<>(Task.adapt(callable, null, null));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return super.invokeAny(Linq.from(tasks).select(p -> Task.adapt(p, null, null)).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return super.invokeAny(Linq.from(tasks).select(p -> Task.adapt(p, null, null)).toList(), timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return super.invokeAll(Linq.from(tasks).select(p -> Task.adapt(p, null, null)).toList());
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return super.invokeAll(Linq.from(tasks).select(p -> Task.adapt(p, null, null)).toList(), timeout, unit);
    }

    public Future<Void> run(Action task) {
        return run(task, null, null);
    }

    public Future<Void> run(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<Void> t = Task.adapt(task, flags, taskId);
        if (t.flags.has(RunFlag.SERIAL) && t.id != null) {
            return runSerialAsync(t, t.id, t.flags, false);
        }
        return submit((Callable<Void>) t);
    }

    @Override
    public void execute(Runnable command) {
        Task<?> task = setTask(command);
        if (task != null && task.flags.has(RunFlag.SERIAL) && task.id != null) {
            runSerialAsync((Task<Object>)task, task.id, task.flags, false);
            return;
        }
        super.execute(Task.adapt(command, null, null));
    }

    public <T> Future<T> run(Func<T> task) {
        return run(task, null, null);
    }

    public <T> Future<T> run(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<T> t = Task.adapt(task, flags, taskId);
        if (t.flags.has(RunFlag.SERIAL) && t.id != null) {
            return runSerialAsync(t, t.id, t.flags, false);
        }
        return submit((Callable<T>) t);
    }

    @SneakyThrows
    public <T> T runAny(Iterable<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) Task.adapt(p, null, null)).toList();
        return timeoutMillis > 0 ? super.invokeAny(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAny(callables);
    }

    @SneakyThrows
    public <T> List<Future<T>> runAll(Iterable<Func<T>> tasks, long timeoutMillis) {
        List<Callable<T>> callables = Linq.from(tasks).select(p -> (Callable<T>) Task.adapt(p, null, null)).toList();
        return timeoutMillis > 0 ? super.invokeAll(callables, timeoutMillis, TimeUnit.MILLISECONDS) : super.invokeAll(callables);
    }

    public <T> CompletionService<T> newCompletionService() {
        return new ExecutorCompletionService<>(this);
    }
    // endregion

    // region v2
    public CompletableFuture<Void> runAsync(Action task) {
        return runAsync(task, null, null);
    }

    public CompletableFuture<Void> runAsync(Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<Void> t = Task.adapt(task, flags, taskId);
        if (t.flags.has(RunFlag.SERIAL) && t.id != null) {
            return runSerialAsync(t, t.id, t.flags, false);
        }
        return CompletableFuture.runAsync(t, asyncExecutor);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<T> t = Task.adapt(task, flags, taskId);
        if (t.flags.has(RunFlag.SERIAL) && t.id != null) {
            return runSerialAsync(t, t.id, t.flags, false);
        }
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
        return runSerialAsync(Task.adapt(task, flags, taskId), taskId, flags, reuse);
    }

    <T> CompletableFuture<T> runSerialAsync(@NonNull Task<T> t, @NonNull Object taskId, FlagsEnum<RunFlag> flags, boolean reuse) {
        AtomicInteger counter = taskSerialCountMap.computeIfAbsent(taskId, k -> new AtomicInteger(0));
        int maxCap = serialQueueCapacity();

        if (counter.incrementAndGet() > maxCap) {
            counter.decrementAndGet();
            serialRejectedCount.increment();
            throw new RejectedExecutionException("Serial task chain for " + taskId
                    + " has exceeded capacity " + maxCap + " in pool " + poolName);
        }
        AtomicReference<CompletableFuture<T>> nextRef = new AtomicReference<>();
        try {
            taskSerialMap.compute(taskId, (k, prev) -> {
                CompletableFuture<T> next;
                if (prev == null) {
                    next = CompletableFuture.supplyAsync(t, asyncExecutor);
                } else {
                    next = ((CompletableFuture<T>) prev).handleAsync((it, ex) -> {
                        if (ex == null) {
                            COMPLETION_RETURNED_VALUE.set(it);
                        }
                        try {
                            return t.get();
                        } finally {
                            COMPLETION_RETURNED_VALUE.remove();
                        }
                    }, this);
                }
                nextRef.set(next);
                return next;
            });
        } catch (Throwable ex) {
            if (counter.decrementAndGet() == 0) {
                taskSerialCountMap.remove(taskId, counter);
            }
            throw ex;
        }
        CompletableFuture<T> next = nextRef.get();
        next.whenComplete((r, e) -> {
            taskSerialMap.compute(taskId, (k2, cur) -> cur == next ? null : cur);
            if (counter.decrementAndGet() == 0) {
                taskSerialCountMap.remove(taskId, counter);
            }
        });
        return next;
    }

    public <T> MultiTaskFuture<T, T> runAnyAsync(Iterable<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = Task.adapt(task, null, null);
            return CompletableFuture.supplyAsync(t, asyncExecutor);
        }).toArray();
        return new MultiTaskFuture<>((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public <T> MultiTaskFuture<Void, T> runAllAsync(Iterable<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = Task.adapt(task, null, null);
            // allOf().join() will hang
            // return wrap(CompletableFuture.supplyAsync(t, this), t.traceId);
            return CompletableFuture.supplyAsync(t, asyncExecutor);
        }).toArray();
        return new MultiTaskFuture<>(CompletableFuture.allOf(futures), futures);
    }
    // endregion

    @SneakyThrows
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        Task<?> task = setTask(r);
        if (task == null) {
            return;
        }
        task.poolManaged = true;
        if (!task.callerRuns) {
            clearTrace();
        }

        FlagsEnum<RunFlag> flags = task.flags;
        if (flags.has(RunFlag.SINGLE)) {
            Object id = task.id;
            if (id == null) {
                throw new InvalidException("SINGLE flag require a taskId");
            }
            if (!runningSingleTasks.add(id)) {
                task.skipExecution = true;
                singleSkipCount.increment();
                log.warn("SingleScope {} -> {} already running", id, flags.name());
                return;
            }
            task.singleLockAcquired = true;
            if (log.isDebugEnabled()) {
                log.debug("CTX acquire {} -> {}", id, flags.name());
            }
        }
        if (flags.has(RunFlag.PRIORITY) && !getQueue().isEmpty()) {
            CpuWatchman.incrSize(this);
        }
        // TransmittableThreadLocal
        if (task.parent != null) {
            task.oldThreadLocalMap = getThreadLocalMap(t);
            task.threadLocalMapSet = true;
            setThreadLocalMap(t, copyThreadLocalMap(task.parent));
        }
        if (flags.has(RunFlag.THREAD_TRACE) || Strings.isNotEmpty(task.traceId)) {
            task.threadTraceStarted = startTaskTrace(task.traceId);
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Task<?> task = getTask(r, true);
        // Default Behavior with Callable
        // The uncaught exception - if one occurs - is considered as a part of this Future.
        // Thus the JDK doesn't try to notify the handler.
        // if (t == null && r instanceof FutureTask) {
        // try {
        // FutureTask<?> f = (FutureTask<?>) r;
        // if (f.isDone()) {
        // f.get();
        // }
        // } catch (CancellationException ce) {
        // t = ce;
        // } catch (ExecutionException ee) {
        // t = ee.getCause();
        // } catch (InterruptedException ie) {
        // Thread.currentThread().interrupt();
        // }
        // if (t != null) {
        // TraceHandler.INSTANCE.log(t);
        // }
        // }
        if (task == null) {
            return;
        }

        try {
            if (task.skipExecution) {
                return;
            }
            Object id = task.id;
            if (task.singleLockAcquired && id != null) {
                runningSingleTasks.remove(id);
                if (log.isDebugEnabled()) {
                    log.debug("CTX release {} -> {}", id, task.flags.name());
                }
            }
            if (task.threadLocalMapSet) {
                setThreadLocalMap(Thread.currentThread(), task.oldThreadLocalMap);
                task.oldThreadLocalMap = null;
                task.threadLocalMapSet = false;
            }
            if (task.threadTraceStarted) {
                endTrace();
                task.threadTraceStarted = false;
            }
        } finally {
            if (!task.callerRuns) {
                clearTrace();
            }
            task.poolManaged = false;
        }
    }

    private static int serialQueueCapacity() {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        int capacity = conf.serialQueueCapacity > 0 ? conf.serialQueueCapacity : checkCapacity(conf.queueCapacity);
        int hardLimit = conf.serialQueueHardLimit > 0 ? conf.serialQueueHardLimit : 100000;
        return Math.max(1, Math.min(capacity, hardLimit));
    }

    private void rejectTask(Runnable r) {
        taskRejectedCount.increment();
        getTask(r, true);
    }

    private void runInCaller(Runnable r) {
        Thread thread = Thread.currentThread();
        Throwable error = null;
        Task<?> task = setTask(r);
        if (task != null) {
            task.callerRuns = true;
        }
        beforeExecute(thread, r);
        try {
            r.run();
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } catch (Error e) {
            error = e;
            throw e;
        } finally {
            afterExecute(r, error);
            if (task != null) {
                task.callerRuns = false;
            }
        }
    }

    private InternalThreadLocalMap getThreadLocalMap(Thread t) {
        if (t instanceof FastThreadLocalThread) {
            return ((FastThreadLocalThread) t).threadLocalMap();
        }
        return SLOW_THREAD_LOCAL_MAP.get();
    }

    @SneakyThrows
    private InternalThreadLocalMap copyThreadLocalMap(InternalThreadLocalMap threadLocalMap) {
        InternalThreadLocalMap copy = INTERNAL_THREAD_LOCAL_MAP_CONSTRUCTOR.newInstance();
        Object[] indexedVariables = ((Object[]) INDEXED_VARIABLES_FIELD.get(threadLocalMap)).clone();
        int variablesToRemoveIndex = InternalThreadLocalMap.VARIABLES_TO_REMOVE_INDEX;
        if (variablesToRemoveIndex < indexedVariables.length && indexedVariables[variablesToRemoveIndex] instanceof Set) {
            indexedVariables[variablesToRemoveIndex] = new HashSet<>((Set<?>) indexedVariables[variablesToRemoveIndex]);
        }
        INDEXED_VARIABLES_FIELD.set(copy, indexedVariables);
        return copy;
    }

    private void setThreadLocalMap(Thread t, InternalThreadLocalMap threadLocalMap) {
        if (t instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) t).setThreadLocalMap(threadLocalMap);
            return;
        }
        if (threadLocalMap == null) {
            SLOW_THREAD_LOCAL_MAP.remove();
            return;
        }
        SLOW_THREAD_LOCAL_MAP.set(threadLocalMap);
    }

    private Task<?> setTask(Runnable r) {
        if (r instanceof FutureTaskAdapter) {
            return ((FutureTaskAdapter<?>) r).task;
        }
        Task<?> task = Task.as(r);
        if (task != null) {
            return task;
        }
        task = taskMap.get(r);
        if (task == null && r instanceof CompletableFuture.AsynchronousCompletionTask) {
            task = readCompletionTask(r);
            if (task != null) {
                taskMap.put(r, task);
            }
        }
        return task;
    }

    private Task<?> readCompletionTask(Runnable r) {
        try {
            Field field = ASYNC_COMPLETION_FN_FIELDS.computeIfAbsent(r.getClass(), k -> Reflects.getFieldMap(k).get("fn"));
            if (field == null || !field.isAccessible()) {
                return null;
            }
            return Task.as(field.get(r));
        } catch (Throwable e) {
            if (log.isDebugEnabled()) {
                log.debug("Read CompletableFuture async task failed {}", r.getClass().getName(), e);
            }
            return null;
        }
    }

    private Task<?> getTask(Runnable r, boolean remove) {
        if (r instanceof FutureTaskAdapter) {
            return ((FutureTaskAdapter<?>) r).task;
        }
        Task<?> task = Task.as(r);
        if (task != null) {
            return task;
        }
        return remove ? taskMap.remove(r) : taskMap.get(r);
    }

    @Override
    public String toString() {
        return String.format("%s%s@%s", POOL_NAME_PREFIX, poolName, Integer.toHexString(hashCode()));
    }

    @Override
    public void shutdown() {
        CpuWatchman.INSTANCE.unregister(this);
        super.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        CpuWatchman.INSTANCE.unregister(this);
        List<Runnable> queued = super.shutdownNow();
        for (Runnable runnable : queued) {
            getTask(runnable, true);
        }
        return queued;
    }

    void recordDiagnosticMetrics() {
        if (!DiagnosticMetrics.isEnabled()) {
            return;
        }
        String tags = "pool=" + sanitizeMetricTag(poolName);
        ThreadQueue queue = (ThreadQueue) getQueue();
        DiagnosticMetrics.record("rx.thread_pool.core.count", getCorePoolSize(), tags);
        DiagnosticMetrics.record("rx.thread_pool.size.count", getPoolSize(), tags);
        DiagnosticMetrics.record("rx.thread_pool.active.count", getActiveCount(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.count", getQueue().size(), tags);
        DiagnosticMetrics.record("rx.thread_pool.completed.count", getCompletedTaskCount(), tags);
        DiagnosticMetrics.record("rx.thread_pool.largest.count", getLargestPoolSize(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.capacity", queue.queueCapacity, tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.remaining", queue.remainingCapacity(), tags);
        DiagnosticMetrics.record("rx.thread_pool.task.rejected.count", taskRejectedCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.block.count", queue.offerBlockCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.block.millis", queue.offerBlockMillis.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.block.max.millis", queue.offerBlockMaxMillis.get(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.rejected.count", queue.offerRejectedCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.caller_runs.count", queue.offerCallerRunsCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.serial.chain.count", taskSerialCountMap.size(), tags);
        DiagnosticMetrics.record("rx.thread_pool.serial.rejected.count", serialRejectedCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.single.skip.count", singleSkipCount.sum(), tags);
    }

    private static String sanitizeMetricTag(String value) {
        if (value == null || value.length() == 0) {
            return "unknown";
        }
        return value.replace(',', '_').replace('\r', ' ').replace('\n', ' ');
    }
}
