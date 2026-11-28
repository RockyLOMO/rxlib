package org.rx.core;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ThreadExecutorMap;
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
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    static final class PoolWorkerThread extends FastThreadLocalThread {
        final ThreadPool ownerPool;

        PoolWorkerThread(ThreadGroup group, Runnable target, String name, ThreadPool ownerPool) {
            super(group, target, name);
            this.ownerPool = ownerPool;
        }
    }

    static final class PoolThreadFactory extends DefaultThreadFactory {
        volatile ThreadPool ownerPool;

        PoolThreadFactory(String poolName, int priority) {
            super(String.format("%s%s", POOL_NAME_PREFIX, poolName), true, priority);
        }

        @Override
        protected Thread newThread(Runnable r, String name) {
            return new PoolWorkerThread(threadGroup, r, name, ownerPool);
        }
    }

    public static class ThreadQueue extends LinkedTransferQueue<Runnable> {
        private static final long serialVersionUID = 4283369202482437480L;
        private static final int SLOT_CALLER_RAN = 0;
        private static final int SLOT_ACQUIRED = 1;
        private static final int SLOT_REJECTED = -1;
        private static final int SLOT_TRY_NON_CORE = -2;

        private static final int WAIT_ACQUIRED = 1;
        private static final int WAIT_TIMEOUT = 2;
        private static final int WAIT_INTERRUPTED = 3;
        private static final int WAIT_SHUTDOWN = 4;

        private ThreadPool pool;
        final int queueCapacity;
        final AtomicInteger counter = new AtomicInteger();
        final Semaphore availableSlots;
        final LongAdder offerBlockCount = new LongAdder();
        final LongAdder offerBlockMillis = new LongAdder();
        final AtomicLong offerBlockMaxMillis = new AtomicLong();
        final LongAdder offerRejectedCount = new LongAdder();
        final LongAdder offerCallerRunsCount = new LongAdder();
        final LongAdder offerReentrantBypassCount = new LongAdder();
        final LongAdder offerNonBlockingBypassCount = new LongAdder();

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
        public boolean add(Runnable r) {
            if (offer(r)) {
                return true;
            }
            throw new IllegalStateException("ThreadPool " + poolName() + " queue full");
        }

        @Override
        public void put(Runnable r) {
            if (!offer(r)) {
                throw new RejectedExecutionException("ThreadPool " + poolName() + " queue put rejected");
            }
        }

        @Override
        public boolean offer(Runnable r, long timeout, TimeUnit unit) {
            Objects.requireNonNull(r, "runnable");
            Objects.requireNonNull(unit, "unit");
            if (isPoolShutdown()) {
                return false;
            }
            boolean acquired;
            try {
                acquired = timeout <= 0L ? availableSlots.tryAcquire() : availableSlots.tryAcquire(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!acquired) {
                return false;
            }
            if (isPoolShutdown()) {
                availableSlots.release();
                return false;
            }
            boolean offered = false;
            counter.incrementAndGet();
            try {
                if (pool != null) {
                    pool.setTask(r);
                }
                offered = super.offer(r);
                return offered;
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
        public void transfer(Runnable e) {
            throw new UnsupportedOperationException("Use ThreadPool with RunFlag.TRANSFER instead");
        }

        @Override
        public boolean tryTransfer(Runnable e) {
            throw new UnsupportedOperationException("Use ThreadPool with RunFlag.TRANSFER instead");
        }

        @Override
        public boolean tryTransfer(Runnable e, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("Use ThreadPool with RunFlag.TRANSFER instead");
        }

        @Override
        public boolean offer(Runnable r) {
            Objects.requireNonNull(r, "runnable");
            int acquireResult = acquireSlot(r);
            if (acquireResult == SLOT_CALLER_RAN) {
                return true;
            }
            if (acquireResult == SLOT_TRY_NON_CORE) {
                return false;
            }
            if (acquireResult < 0) {
                RejectedExecutionException ex = new RejectedExecutionException("ThreadPool " + poolName() + " queue offer rejected");
                if (pool != null) {
                    pool.rejectTask(r, ex);
                }
                throw ex;
            }

            boolean offered = false;
            counter.incrementAndGet();
            try {
                Task<?> task = pool != null ? pool.setTask(r) : null;
                if (task != null && task.flags.has(RunFlag.TRANSFER)) {
                    if (isNonBlockingSubmitThread() || (pool != null && pool.isCurrentPoolWorker())) {
                        offered = super.offer(r);
                        return offered;
                    }
                    for (;;) {
                        if (isPoolShutdown()) {
                            RejectedExecutionException ex = new RejectedExecutionException("ThreadPool " + poolName() + " is shutdown during TRANSFER");
                            if (pool != null) {
                                pool.rejectTask(r, ex);
                            }
                            throw ex;
                        }
                        if (super.tryTransfer(r, 500L, TimeUnit.MILLISECONDS)) {
                            offered = true;
                            return true;
                        }
                    }
                }
                offered = super.offer(r);
                return offered;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RejectedExecutionException ex = new RejectedExecutionException("ThreadPool " + poolName() + " queue offer interrupted", e);
                if (pool != null) {
                    pool.rejectTask(r, ex);
                }
                throw ex;
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
            if (isPoolShutdown()) {
                offerRejectedCount.increment();
                return SLOT_REJECTED;
            }
            if (availableSlots.tryAcquire()) {
                if (isPoolShutdown()) {
                    availableSlots.release();
                    offerRejectedCount.increment();
                    return SLOT_REJECTED;
                }
                return SLOT_ACQUIRED;
            }

            if (isNonBlockingSubmitThread()) {
                offerNonBlockingBypassCount.increment();
                if (log.isWarnEnabled()) {
                    log.warn("Non-blocking caller hit full queue[{}/{}], bypass blocking/caller-runs, pool={}, task={}",
                            counter.get(), queueCapacity, poolName(), r);
                }
                return SLOT_TRY_NON_CORE;
            }

            RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
            ThreadPoolQueueOfferMode mode = conf.queueOfferMode != null ? conf.queueOfferMode : ThreadPoolQueueOfferMode.BLOCK;
            long timeoutMillis = Math.max(0L, conf.queueOfferTimeoutMillis);

            if (pool != null && pool.isCurrentPoolWorker()) {
                if (mode == ThreadPoolQueueOfferMode.CALLER_RUNS) {
                    return runInCallerIfAccepted(r);
                }
                offerReentrantBypassCount.increment();
                if (log.isWarnEnabled()) {
                    log.warn("Same-pool worker reentrant submit hit full queue[{}/{}], bypass block to prevent deadlock, pool={}, task={}",
                            counter.get(), queueCapacity, poolName(), r);
                }
                return SLOT_TRY_NON_CORE;
            }

            long startNanos = System.nanoTime();
            if (mode == ThreadPoolQueueOfferMode.BLOCK) {
                return blockUntilSlot(r, startNanos);
            }

            if (timeoutMillis > 0L) {
                int waitResult = waitForSlot(r, timeoutMillis, startNanos);
                if (waitResult == WAIT_ACQUIRED) {
                    return SLOT_ACQUIRED;
                }
                if (waitResult == WAIT_INTERRUPTED || waitResult == WAIT_SHUTDOWN) {
                    offerRejectedCount.increment();
                    return SLOT_REJECTED;
                }
            } else {
                if (Thread.currentThread().isInterrupted() || isPoolShutdown()) {
                    offerRejectedCount.increment();
                    return SLOT_REJECTED;
                }
            }

            recordOfferBlock(startNanos);
            if (mode == ThreadPoolQueueOfferMode.CALLER_RUNS) {
                return runInCallerIfAccepted(r);
            }

            offerRejectedCount.increment();
            if (log.isWarnEnabled()) {
                log.warn("Reject task because queue[{}/{}] is full after {}ms, pool={}, task={}",
                        counter.get(), queueCapacity, timeoutMillis, poolName(), r);
            }
            return SLOT_REJECTED;
        }

        private int runInCallerIfAccepted(Runnable r) {
            if (isPoolShutdown()) {
                offerRejectedCount.increment();
                return SLOT_REJECTED;
            }
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
            return SLOT_CALLER_RAN;
        }

        private int blockUntilSlot(Runnable r, long startNanos) {
            boolean logged = false;
            for (;;) {
                if (isPoolShutdown()) {
                    offerRejectedCount.increment();
                    return SLOT_REJECTED;
                }
                try {
                    if (availableSlots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        if (isPoolShutdown()) {
                            availableSlots.release();
                            offerRejectedCount.increment();
                            return SLOT_REJECTED;
                        }
                        recordOfferBlock(startNanos);
                        if (logged && log.isDebugEnabled()) {
                            log.debug("Wait poll ok");
                        }
                        return SLOT_ACQUIRED;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    offerRejectedCount.increment();
                    return SLOT_REJECTED;
                }
                if (!logged) {
                    log.warn("Block caller thread until queue[{}/{}] polled then offer {}", counter.get(), queueCapacity, r);
                    logged = true;
                }
            }
        }

        private int waitForSlot(Runnable r, long timeoutMillis, long startNanos) {
            boolean logged = false;
            long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            for (;;) {
                if (isPoolShutdown()) {
                    return WAIT_SHUTDOWN;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return WAIT_TIMEOUT;
                }
                long waitNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(500L));
                try {
                    if (availableSlots.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                        if (isPoolShutdown()) {
                            availableSlots.release();
                            return WAIT_SHUTDOWN;
                        }
                        recordOfferBlock(startNanos);
                        return WAIT_ACQUIRED;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WAIT_INTERRUPTED;
                }
                if (!logged) {
                    log.warn("Block caller thread up to {}ms until queue[{}/{}] polled then offer {}",
                            timeoutMillis, counter.get(), queueCapacity, r);
                    logged = true;
                }
            }
        }

        @Override
        public Iterator<Runnable> iterator() {
            final Iterator<Runnable> delegate = super.iterator();
            return new Iterator<Runnable>() {
                private Runnable lastReturned;

                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public Runnable next() {
                    lastReturned = delegate.next();
                    return lastReturned;
                }

                @Override
                public void remove() {
                    Runnable target = lastReturned;
                    if (target == null) {
                        throw new IllegalStateException();
                    }
                    lastReturned = null;
                    ThreadQueue.this.remove(target);
                }
            };
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

        static <T> Task<T> adaptWithTrace(Callable<T> fn, String traceId) {
            return new Task<>(fn, null, null, traceId);
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
        InternalThreadLocalMap parent;
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
            this(fn, flags, id, traceId());
        }

        private Task(Callable<T> fn, FlagsEnum<RunFlag> flags, Object id, String traceId) {
            if (flags == null) {
                flags = RunFlag.NONE.flags();
            }
            if (flags.has(RunFlag.SINGLE) && id == null) {
                throw new InvalidException("SINGLE flag require a taskId");
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
            if (flags.has(RunFlag.INHERIT_FAST_THREAD_LOCALS)) {
                InternalThreadLocalMap current = InternalThreadLocalMap.getIfSet();
                this.parent = current != null ? copyThreadLocalMap(current) : null;
            } else {
                this.parent = null;
            }
            this.traceId = traceId;
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

        void fail(Throwable error) {
            setException(error);
        }
    }

    static final class AsyncTaskRunnable<T> implements Runnable {
        final Task<T> task;
        final CompletableFuture<T> future;

        AsyncTaskRunnable(Task<T> task, CompletableFuture<T> future) {
            this.task = task;
            this.future = future;
        }

        @Override
        public void run() {
            if (future.isDone()) {
                return;
            }
            try {
                future.complete(task.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }

        void fail(Throwable error) {
            future.completeExceptionally(error);
        }
    }

    static final class SerialChain {
        final Object taskId;
        final ArrayDeque<SerialNode<?>> queue = new ArrayDeque<>(4);
        boolean running;
        int pendingCount;
        Object lastResult;
        boolean lastSucceeded;
        boolean prevCancelledBeforeNext;

        SerialChain(Object taskId) {
            this.taskId = taskId;
        }
    }

    static final class SerialFuture<T> extends CompletableFuture<T> {
        final SerialNode<T> node;

        SerialFuture(SerialNode<T> node) {
            this.node = node;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                node.markCancelledIfQueued();
            }
            return cancelled;
        }
    }

    static final class SerialNode<T> {
        static final int QUEUED = 0;
        static final int RUNNING = 1;
        static final int CANCELLED = 2;

        final ThreadPool targetPool;
        final Object taskId;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final SerialFuture<T> userFuture;
        volatile Task<T> task;
        boolean clearPrevResult;

        SerialNode(ThreadPool targetPool, Object taskId, Task<T> task) {
            this.targetPool = targetPool;
            this.taskId = taskId;
            this.task = task;
            this.userFuture = new SerialFuture<>(this);
        }

        void markCancelledIfQueued() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                task = null;
            }
        }

        boolean isCancelledOrDone() {
            return state.get() == CANCELLED || userFuture.isDone();
        }
    }

    static final class SerialRunnable<T> implements Runnable {
        final SerialNode<T> node;
        final Task<T> task;
        final Object prevResult;
        final boolean hasPrevResult;
        final AtomicBoolean advanced = new AtomicBoolean();
        volatile boolean inlineManaged;
        Object outcomeValue;
        boolean outcomeSucceeded;

        SerialRunnable(SerialNode<T> node, Task<T> task, Object prevResult, boolean hasPrevResult) {
            this.node = node;
            this.task = task;
            this.prevResult = prevResult;
            this.hasPrevResult = hasPrevResult;
        }

        boolean isCancelledBeforeStart() {
            return node.state.get() == SerialNode.CANCELLED || node.userFuture.isDone();
        }

        @Override
        public void run() {
            if (!node.state.compareAndSet(SerialNode.QUEUED, SerialNode.RUNNING)) {
                outcomeValue = null;
                outcomeSucceeded = false;
                return;
            }
            if (node.userFuture.isDone()) {
                outcomeValue = null;
                outcomeSucceeded = false;
                node.task = null;
                return;
            }
            if (hasPrevResult) {
                COMPLETION_RETURNED_VALUE.set(prevResult);
            }
            try {
                T val = task.call();
                outcomeValue = val;
                outcomeSucceeded = true;
                node.userFuture.complete(val);
            } catch (Throwable e) {
                outcomeValue = null;
                outcomeSucceeded = false;
                node.userFuture.completeExceptionally(e);
            } finally {
                COMPLETION_RETURNED_VALUE.remove();
                node.task = null;
            }
        }

        void afterPoolExecute() {
            if (!inlineManaged) {
                advanceOnce(outcomeValue, outcomeSucceeded);
            }
        }

        void failAndAdvance(Throwable error) {
            outcomeValue = null;
            outcomeSucceeded = false;
            node.task = null;
            node.userFuture.completeExceptionally(error);
            if (!inlineManaged) {
                advanceOnce(null, false);
            }
        }

        void advanceOnce(Object result, boolean succeeded) {
            if (advanced.compareAndSet(false, true)) {
                advanceSerialChain(node.taskId, result, succeeded);
            }
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
    private static final FastThreadLocal<Boolean> NON_BLOCKING_SUBMIT = new FastThreadLocal<>();
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
    static final Map<Object, SerialChain> taskSerialMap = new ConcurrentHashMap<>();

    static boolean isNonBlockingSubmitThread() {
        if (Boolean.TRUE.equals(NON_BLOCKING_SUBMIT.getIfExists())) {
            return true;
        }
        Thread current = Thread.currentThread();
        if (current instanceof WheelTimer.TimerWorkerThread) {
            return true;
        }
        return ThreadExecutorMap.currentExecutor() instanceof EventLoop;
    }

    private static void restoreNonBlockingFlag(Boolean prev) {
        if (prev == null) {
            NON_BLOCKING_SUBMIT.remove();
        } else {
            NON_BLOCKING_SUBMIT.set(prev);
        }
    }

    static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

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
                new ThreadQueue(checkCapacity(queueCapacity)), new PoolThreadFactory(poolName, Thread.NORM_PRIORITY), (r, executor) -> {
                    ThreadPool pool = executor instanceof ThreadPool ? (ThreadPool) executor : null;
                    if (executor.isShutdown()) {
                        if (pool != null) {
                            pool.rejectTask(r, new RejectedExecutionException("ThreadPool " + poolName + " is shutdown"));
                        }
                        log.warn("ThreadPool {} is shutdown", poolName);
                        throw new RejectedExecutionException("ThreadPool " + poolName + " is shutdown");
                    }
                    RejectedExecutionException error = new RejectedExecutionException("ThreadPool " + poolName + " rejected task");
                    if (pool != null) {
                        pool.rejectTask(r, error);
                    }
                    throw error;
                });
        super.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        ThreadFactory factory = getThreadFactory();
        if (factory instanceof PoolThreadFactory) {
            ((PoolThreadFactory) factory).ownerPool = this;
        }
        ((ThreadQueue) super.getQueue()).pool = this;
        this.poolName = poolName;
        this.minIdleSize = resolveMinIdleSize(minIdleSize);
        this.maxPoolSize = resolveMaxPoolSize(initSize, minIdleSize, maxPoolSize);
        this.resizeStep = resolveResizeStep(resizeStep);

        dynamicSizeByCpuLoad(cpuWaterMark);
    }

    boolean isCurrentPoolWorker() {
        Thread t = Thread.currentThread();
        return t instanceof PoolWorkerThread && ((PoolWorkerThread) t).ownerPool == this;
    }

    public boolean tryExecute(@NonNull Runnable command) {
        if (isShutdown()) {
            return false;
        }
        Boolean prev = NON_BLOCKING_SUBMIT.getIfExists();
        NON_BLOCKING_SUBMIT.set(Boolean.TRUE);
        try {
            execute(command);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        } finally {
            restoreNonBlockingFlag(prev);
        }
    }

    public <T> Future<T> trySubmit(@NonNull Callable<T> task) {
        Boolean prev = NON_BLOCKING_SUBMIT.getIfExists();
        NON_BLOCKING_SUBMIT.set(Boolean.TRUE);
        try {
            return submit(task);
        } finally {
            restoreNonBlockingFlag(prev);
        }
    }

    public CompletableFuture<Void> tryRunAsync(@NonNull Action task) {
        return tryRunAsync(task, null, null);
    }

    public CompletableFuture<Void> tryRunAsync(@NonNull Action task, Object taskId, FlagsEnum<RunFlag> flags) {
        Boolean prev = NON_BLOCKING_SUBMIT.getIfExists();
        NON_BLOCKING_SUBMIT.set(Boolean.TRUE);
        try {
            return runAsync(task, taskId, flags);
        } catch (RejectedExecutionException e) {
            return failedFuture(e);
        } finally {
            restoreNonBlockingFlag(prev);
        }
    }

    public <T> CompletableFuture<T> tryRunAsync(@NonNull Func<T> task) {
        return tryRunAsync(task, null, null);
    }

    public <T> CompletableFuture<T> tryRunAsync(@NonNull Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Boolean prev = NON_BLOCKING_SUBMIT.getIfExists();
        NON_BLOCKING_SUBMIT.set(Boolean.TRUE);
        try {
            return runAsync(task, taskId, flags);
        } catch (RejectedExecutionException e) {
            return failedFuture(e);
        } finally {
            restoreNonBlockingFlag(prev);
        }
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
        if (command instanceof SerialRunnable || command instanceof AsyncTaskRunnable) {
            super.execute(command);
            return;
        }
        Task<?> task = setTask(command);
        if (task != null && task.flags.has(RunFlag.SERIAL) && task.id != null) {
            runSerialAsync((Task<Object>) task, task.id, task.flags, false);
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
        return submitAsyncTask(t);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task) {
        return runAsync(task, null, null);
    }

    public <T> CompletableFuture<T> runAsync(Func<T> task, Object taskId, FlagsEnum<RunFlag> flags) {
        Task<T> t = Task.adapt(task, flags, taskId);
        if (t.flags.has(RunFlag.SERIAL) && t.id != null) {
            return runSerialAsync(t, t.id, t.flags, false);
        }
        return submitAsyncTask(t);
    }

    private <T> CompletableFuture<T> submitAsyncTask(Task<T> t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        AsyncTaskRunnable<T> runnable = new AsyncTaskRunnable<>(t, future);
        super.execute(runnable);
        return future;
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
        int maxCap = serialQueueCapacity();
        SerialNode<T> node = new SerialNode<>(this, taskId, t);
        SerialRunnable<?>[] toDispatch = new SerialRunnable<?>[1];
        boolean[] rejected = new boolean[1];

        taskSerialMap.compute(taskId, (k, chain) -> {
            if (chain == null) {
                chain = new SerialChain(taskId);
            } else {
                purgeCancelledQueuedNodes(chain);
            }
            if (chain.pendingCount >= maxCap) {
                rejected[0] = true;
                return chain.running || !chain.queue.isEmpty() ? chain : null;
            }
            chain.pendingCount++;
            if (!chain.running) {
                chain.running = true;
                boolean hasPrev = chain.lastSucceeded && !chain.prevCancelledBeforeNext;
                Object prevVal = hasPrev ? chain.lastResult : null;
                chain.prevCancelledBeforeNext = false;
                toDispatch[0] = new SerialRunnable<>(node, t, prevVal, hasPrev);
            } else {
                node.clearPrevResult = chain.prevCancelledBeforeNext;
                chain.prevCancelledBeforeNext = false;
                chain.queue.addLast(node);
            }
            return chain;
        });

        if (rejected[0]) {
            serialRejectedCount.increment();
            throw new RejectedExecutionException("Serial task chain for " + taskId
                    + " has exceeded capacity " + maxCap + " in pool " + poolName);
        }

        SerialRunnable<?> first = toDispatch[0];
        if (first != null) {
            try {
                super.execute(first);
            } catch (RejectedExecutionException e) {
                first.failAndAdvance(e);
                throw e;
            } catch (Throwable e) {
                first.failAndAdvance(e);
                throw sneakyThrow(e);
            }
        }
        return node.userFuture;
    }

    private static void purgeCancelledQueuedNodes(SerialChain chain) {
        if (chain.queue.isEmpty()) {
            return;
        }
        boolean sawCancelled = false;
        int size = chain.queue.size();
        for (int i = 0; i < size; i++) {
            SerialNode<?> n = chain.queue.pollFirst();
            if (n.isCancelledOrDone()) {
                n.markCancelledIfQueued();
                chain.pendingCount--;
                sawCancelled = true;
            } else {
                if (sawCancelled) {
                    n.clearPrevResult = true;
                    sawCancelled = false;
                }
                chain.queue.addLast(n);
            }
        }
        if (sawCancelled) {
            chain.prevCancelledBeforeNext = true;
        }
    }

    private static void advanceSerialChain(Object taskId, Object completedResult, boolean completedSucceeded) {
        Object prevResult = completedResult;
        boolean prevSucceeded = completedSucceeded;
        while (true) {
            final Object curPrevResult = prevResult;
            final boolean curPrevSucceeded = prevSucceeded;
            SerialRunnable<?>[] nextHolder = new SerialRunnable<?>[1];
            taskSerialMap.compute(taskId, (k, chain) -> {
                if (chain == null) {
                    return null;
                }
                chain.pendingCount--;
                chain.lastResult = curPrevSucceeded ? curPrevResult : null;
                chain.lastSucceeded = curPrevSucceeded;

                while (!chain.queue.isEmpty()) {
                    SerialNode<?> candidate = chain.queue.pollFirst();
                    if (candidate.clearPrevResult) {
                        chain.lastResult = null;
                        chain.lastSucceeded = false;
                    }
                    if (candidate.isCancelledOrDone()) {
                        candidate.markCancelledIfQueued();
                        chain.pendingCount--;
                        chain.lastResult = null;
                        chain.lastSucceeded = false;
                        continue;
                    }
                    Task<?> candidateTask = candidate.task;
                    if (candidateTask == null) {
                        chain.pendingCount--;
                        chain.lastResult = null;
                        chain.lastSucceeded = false;
                        continue;
                    }
                    nextHolder[0] = new SerialRunnable(candidate, candidateTask, chain.lastResult, chain.lastSucceeded);
                    return chain;
                }

                chain.running = false;
                chain.lastResult = null;
                chain.lastSucceeded = false;
                chain.prevCancelledBeforeNext = false;
                return chain.pendingCount <= 0 ? null : chain;
            });

            SerialRunnable<?> next = nextHolder[0];
            if (next == null) {
                return;
            }
            if (next.isCancelledBeforeStart()) {
                next.node.markCancelledIfQueued();
                prevResult = null;
                prevSucceeded = false;
                continue;
            }
            ThreadPool targetPool = next.node.targetPool;
            if (targetPool.isShutdown()) {
                RejectedExecutionException ex = new RejectedExecutionException("ThreadPool " + targetPool.poolName + " is shutdown");
                next.node.task = null;
                next.node.userFuture.completeExceptionally(ex);
                prevResult = null;
                prevSucceeded = false;
                continue;
            }
            try {
                targetPool.submitSerialContinuation(next);
                return;
            } catch (RejectedExecutionException e) {
                if (!targetPool.isShutdown()
                        && (targetPool.isCurrentPoolWorker()
                        || (targetPool.isCallerRunsMode() && !isNonBlockingSubmitThread()))) {
                    next.inlineManaged = true;
                    try {
                        targetPool.runSerialInline(next);
                    } catch (Throwable ignored) {
                        // outcome & userFuture already finalized
                    }
                    prevResult = next.outcomeValue;
                    prevSucceeded = next.outcomeSucceeded;
                    continue;
                }
                next.node.task = null;
                next.node.userFuture.completeExceptionally(e);
                prevResult = null;
                prevSucceeded = false;
            }
        }
    }

    private void submitSerialContinuation(SerialRunnable<?> next) {
        Boolean prev = NON_BLOCKING_SUBMIT.getIfExists();
        NON_BLOCKING_SUBMIT.set(Boolean.TRUE);
        try {
            super.execute(next);
        } finally {
            restoreNonBlockingFlag(prev);
        }
    }

    private boolean isCallerRunsMode() {
        ThreadPoolQueueOfferMode mode = RxConfig.INSTANCE.threadPool.queueOfferMode;
        return mode == ThreadPoolQueueOfferMode.CALLER_RUNS;
    }

    public <T> MultiTaskFuture<T, T> runAnyAsync(Iterable<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = Task.adapt(task, null, null);
            return submitAsyncTask(t);
        }).toArray();
        return new MultiTaskFuture<>((CompletableFuture<T>) CompletableFuture.anyOf(futures), futures);
    }

    public <T> MultiTaskFuture<Void, T> runAllAsync(Iterable<Func<T>> tasks) {
        CompletableFuture<T>[] futures = Linq.from(tasks).select(task -> {
            Task<T> t = Task.adapt(task, null, null);
            return submitAsyncTask(t);
        }).toArray();
        return new MultiTaskFuture<>(CompletableFuture.allOf(futures), futures);
    }
    // endregion

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        Task<?> task = setTask(r);
        if (task == null) {
            return;
        }
        task.poolManaged = true;
        try {
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
                InternalThreadLocalMap snapshot = task.parent;
                task.parent = null;
                setThreadLocalMap(t, snapshot);
            }
            if (flags.has(RunFlag.THREAD_TRACE) || Strings.isNotEmpty(task.traceId)) {
                task.threadTraceStarted = startTaskTrace(task.traceId);
            }
            afterBeforeExecuteSetup(t, r, task);
        } catch (Throwable ex) {
            cleanupTaskContext(r, task);
            failUnstartedTask(r, ex);
            throw sneakyThrow(ex);
        }
    }

    void afterBeforeExecuteSetup(Thread t, Runnable r, Task<?> task) {
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Task<?> task = getTask(r, true);
        if (task != null) {
            cleanupTaskContext(r, task);
        }
        if (r instanceof SerialRunnable) {
            ((SerialRunnable<?>) r).afterPoolExecute();
        }
    }

    private void cleanupTaskContext(Runnable r, Task<?> task) {
        getTask(r, true);
        try {
            if (task.singleLockAcquired && task.id != null) {
                runningSingleTasks.remove(task.id);
                task.singleLockAcquired = false;
                if (log.isDebugEnabled()) {
                    log.debug("CTX release {} -> {}", task.id, task.flags.name());
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
            task.parent = null;
        } finally {
            if (!task.callerRuns) {
                clearTrace();
            }
            task.poolManaged = false;
        }
    }

    private void failUnstartedTask(Runnable r, Throwable error) {
        if (r instanceof FutureTaskAdapter) {
            ((FutureTaskAdapter<?>) r).fail(error);
        } else if (r instanceof AsyncTaskRunnable) {
            ((AsyncTaskRunnable<?>) r).fail(error);
        } else if (r instanceof SerialRunnable) {
            ((SerialRunnable<?>) r).failAndAdvance(error);
        }
    }

    private static int serialQueueCapacity() {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        int capacity = conf.serialQueueCapacity > 0 ? conf.serialQueueCapacity : checkCapacity(conf.queueCapacity);
        int hardLimit = conf.serialQueueHardLimit > 0 ? conf.serialQueueHardLimit : 100000;
        return Math.max(1, Math.min(capacity, hardLimit));
    }

    private void rejectTask(Runnable r, Throwable error) {
        taskRejectedCount.increment();
        getTask(r, true);
        if (error != null && r instanceof AsyncTaskRunnable) {
            ((AsyncTaskRunnable<?>) r).fail(error);
        }
    }

    private void runInCaller(Runnable r) {
        runInCallerWithFlag(r, true);
    }

    private void runSerialInline(SerialRunnable<?> sr) {
        runInCallerWithFlag(sr, !isCurrentPoolWorker());
    }

    private void runInCallerWithFlag(Runnable r, boolean preserveCallerTrace) {
        Thread thread = Thread.currentThread();
        Throwable error = null;
        Task<?> task = setTask(r);
        if (task != null) {
            task.callerRuns = preserveCallerTrace;
        }
        boolean started = false;
        try {
            beforeExecute(thread, r);
            started = true;
            r.run();
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } catch (Error e) {
            error = e;
            throw e;
        } finally {
            if (started) {
                afterExecute(r, error);
            }
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
    private static InternalThreadLocalMap copyThreadLocalMap(InternalThreadLocalMap threadLocalMap) {
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
        if (r instanceof AsyncTaskRunnable) {
            return ((AsyncTaskRunnable<?>) r).task;
        }
        if (r instanceof SerialRunnable) {
            return ((SerialRunnable<?>) r).task;
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
        if (r instanceof AsyncTaskRunnable) {
            return ((AsyncTaskRunnable<?>) r).task;
        }
        if (r instanceof SerialRunnable) {
            return ((SerialRunnable<?>) r).task;
        }
        Task<?> task = Task.as(r);
        if (task != null) {
            return task;
        }
        return remove ? taskMap.remove(r) : taskMap.get(r);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
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
        RejectedExecutionException shutdownEx = null;
        for (Runnable runnable : queued) {
            getTask(runnable, true);
            if (runnable instanceof SerialRunnable) {
                if (shutdownEx == null) {
                    shutdownEx = new RejectedExecutionException("ThreadPool " + poolName + " shutdownNow");
                }
                ((SerialRunnable<?>) runnable).failAndAdvance(shutdownEx);
            }
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
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.reentrant_bypass.count", queue.offerReentrantBypassCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.queue.offer.non_blocking_bypass.count", queue.offerNonBlockingBypassCount.sum(), tags);
        DiagnosticMetrics.record("rx.thread_pool.serial.chain.count", taskSerialMap.size(), tags);
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
