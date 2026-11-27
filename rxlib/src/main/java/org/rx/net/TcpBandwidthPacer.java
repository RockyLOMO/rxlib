package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * EventLoop-confined, zero-copy TCP pacing queue backed by a shared {@link BandwidthLimiter}.
 * Coordinates multi-reason read pauses via {@link Sockets#READ_PAUSE_BANDWIDTH} and preserves
 * ordered delivery across half-close / EOF transitions.
 */
public final class TcpBandwidthPacer {
    private static final long MAX_TIMER_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);
    private static final int MAX_CONN_PENDING_TASKS = 256;
    private static final int MIN_CONN_PENDING_BYTES = 512 * 1024;

    private final Channel pauseChannel;
    private final Channel writeChannel;
    private final BooleanSupplier writeReadySupplier;
    private final BandwidthLimiter.Direction direction;
    private final Supplier<BandwidthLimiter> limiterSupplier;
    private final Consumer<ByteBuf> emitter;
    private final Runnable drainTask = this::onScheduledDrain;
    private final ArrayDeque<ByteBuf> queue = new ArrayDeque<>();
    private final int maxConnPendingBytes;

    private volatile int queuedBytes;
    private volatile int queuedCount;
    private int reservedLimiterBytes;
    private int reservedLimiterTasks;
    private BandwidthLimiter reservedLimiter;
    private boolean readPaused;
    private boolean pacerRegistered;
    private boolean eofPending;
    private Runnable eofCallback;
    private volatile boolean closed;
    private ScheduledFuture<?> timer;

    public TcpBandwidthPacer(Channel pauseChannel, Channel writeChannel,
                             BandwidthLimiter.Direction direction,
                             Supplier<BandwidthLimiter> limiterSupplier,
                             Consumer<ByteBuf> emitter) {
        this(pauseChannel, writeChannel, null, direction, limiterSupplier, emitter);
    }

    public TcpBandwidthPacer(Channel pauseChannel, Channel writeChannel,
                             BooleanSupplier writeReadySupplier,
                             BandwidthLimiter.Direction direction,
                             Supplier<BandwidthLimiter> limiterSupplier,
                             Consumer<ByteBuf> emitter) {
        this.pauseChannel = pauseChannel;
        this.writeChannel = writeChannel;
        this.writeReadySupplier = writeReadySupplier;
        this.direction = direction;
        this.limiterSupplier = limiterSupplier;
        this.emitter = emitter;
        Channel waterMarkChannel = writeChannel != null ? writeChannel : pauseChannel;
        int highWater = waterMarkChannel != null ? waterMarkChannel.config().getWriteBufferHighWaterMark() : MIN_CONN_PENDING_BYTES;
        this.maxConnPendingBytes = Math.max(MIN_CONN_PENDING_BYTES, highWater * 2);
    }

    public boolean hasPending() {
        return queuedCount > 0;
    }

    public int queuedBytes() {
        return queuedBytes;
    }

    /**
     * Enqueues or immediately emits {@code buf}. Takes ownership of one reference to {@code buf}.
     * Returns false only if the connection had to be closed due to hard queue overflow.
     */
    public boolean offer(ByteBuf buf) {
        if (closed) {
            ReferenceCountUtil.release(buf);
            return false;
        }
        int size = buf.readableBytes();
        if (size <= 0) {
            if (queue.isEmpty()) {
                emitter.accept(buf);
            } else {
                ReferenceCountUtil.release(buf);
            }
            return true;
        }

        BandwidthLimiter limiter = limiterSupplier.get();
        if (queue.isEmpty() && (limiter == null || !limiter.isEnabled(direction))) {
            emitter.accept(buf);
            return true;
        }

        // Fast path: empty queue, target writable, fits in a single chunk, and tokens available immediately.
        if (queue.isEmpty() && isWriteTargetReady()
                && size <= limiter.recommendedTcpChunkBytes(direction)
                && limiter.tryConsumeTcp(direction, size) == 0L) {
            emitter.accept(buf);
            return true;
        }

        if (!enqueue(buf, size, limiter)) {
            ReferenceCountUtil.release(buf);
            if (limiter != null) {
                limiter.recordTcpQueueOverflow(direction, size);
            }
            close();
            if (pauseChannel != null && pauseChannel.isOpen()) {
                pauseChannel.close();
            }
            if (writeChannel != null && writeChannel != pauseChannel && writeChannel.isOpen()) {
                writeChannel.close();
            }
            return false;
        }

        drain();
        return true;
    }

    public void onWritabilityChanged() {
        if (closed) {
            return;
        }
        EventExecutor executor = ownerExecutor();
        if (executor != null && !executor.inEventLoop()) {
            if (!executor.isShuttingDown()) {
                executor.execute(this::onWritabilityChanged);
            }
            return;
        }
        if (!closed && !queue.isEmpty() && isWriteTargetReady()) {
            drain();
        }
    }

    /**
     * Defers half-close or close-on-flushed until all paced bytes are emitted.
     * Returns true if EOF was deferred; false if queue is already empty (caller proceeds immediately).
     */
    public boolean deferEof(Runnable callback) {
        if (closed || queue.isEmpty()) {
            return false;
        }
        eofPending = true;
        eofCallback = callback;
        if ((timer == null || timer.isDone()) && isWriteTargetReady()) {
            timer = null;
            drain();
        }
        return true;
    }

    public void close() {
        if (closed) {
            return;
        }
        EventExecutor executor = ownerExecutor();
        if (executor != null && !executor.inEventLoop()) {
            if (!executor.isShuttingDown()) {
                executor.execute(this::close);
            }
            return;
        }
        close0();
    }

    private void close0() {
        if (closed) {
            return;
        }
        closed = true;
        cancelTimer();
        eofPending = false;
        eofCallback = null;
        ByteBuf buf;
        while ((buf = queue.pollFirst()) != null) {
            ReferenceCountUtil.release(buf);
        }
        queuedBytes = 0;
        queuedCount = 0;
        releaseAllReserved();
        unregisterPacer();
        resumeReadIfPaused();
    }

    private boolean enqueue(ByteBuf buf, int size, BandwidthLimiter limiter) {
        if (queue.size() >= MAX_CONN_PENDING_TASKS || size > maxConnPendingBytes - queuedBytes) {
            return false;
        }
        if (limiter != null) {
            if (reservedLimiter != null && reservedLimiter != limiter) {
                releaseAllReserved();
                unregisterPacer();
            }
            if (!limiter.reservePendingTcp(size, 1)) {
                return false;
            }
            reservedLimiter = limiter;
            reservedLimiterBytes += size;
            reservedLimiterTasks++;
            registerPacer(limiter);
        }
        queue.addLast(buf);
        queuedCount = queue.size();
        queuedBytes += size;
        pauseReadIfNeeded();
        return true;
    }

    private void onScheduledDrain() {
        timer = null;
        if (!closed) {
            drain();
        }
    }

    private void drain() {
        if (closed) {
            return;
        }
        if (queue.isEmpty()) {
            finishDrained();
            return;
        }
        if (writeChannel != null && !writeChannel.isActive()) {
            close0();
            return;
        }
        if (!isWriteTargetReady()) {
            return;
        }

        BandwidthLimiter limiter = limiterSupplier.get();
        if (limiter == null || !limiter.isEnabled(direction)) {
            while (!closed && !queue.isEmpty() && isWriteTargetReady()) {
                ByteBuf buf = queue.pollFirst();
                queuedCount = queue.size();
                int len = buf.readableBytes();
                queuedBytes -= len;
                releaseReserved(len, 1);
                emitter.accept(buf);
            }
            if (queue.isEmpty()) {
                finishDrained();
            }
            return;
        }

        int turnBudget = limiter.fairTurnQuantumBytes(direction);
        int sentThisTurn = 0;

        while (!closed && !queue.isEmpty() && isWriteTargetReady()) {
            ByteBuf head = queue.peekFirst();
            int readable = head.readableBytes();
            if (readable <= 0) {
                queue.pollFirst();
                queuedCount = queue.size();
                ReferenceCountUtil.release(head);
                releaseReserved(0, 1);
                continue;
            }
            int chunkMax = limiter.recommendedTcpChunkBytes(direction);
            int toSend = Math.min(readable, chunkMax);
            if (sentThisTurn > 0 && sentThisTurn + toSend > turnBudget) {
                // Yield one EventLoop turn so peer connections sharing this limiter also get service.
                scheduleDrain(TimeUnit.MILLISECONDS.toNanos(1L));
                return;
            }

            long waitNanos = limiter.tryConsumeTcp(direction, toSend);
            if (waitNanos > 0L) {
                scheduleDrain(waitNanos);
                return;
            }

            ByteBuf out;
            int tasksDone;
            if (toSend == readable) {
                out = queue.pollFirst();
                queuedCount = queue.size();
                tasksDone = 1;
            } else {
                out = head.readRetainedSlice(toSend);
                tasksDone = 0;
            }
            queuedBytes -= toSend;
            sentThisTurn += toSend;
            releaseReserved(toSend, tasksDone);
            emitter.accept(out);
        }

        if (queue.isEmpty()) {
            finishDrained();
        }
    }

    private void finishDrained() {
        cancelTimer();
        queuedBytes = 0;
        queuedCount = 0;
        releaseAllReserved();
        unregisterPacer();
        resumeReadIfPaused();
        if (eofPending && !closed) {
            eofPending = false;
            Runnable cb = eofCallback;
            eofCallback = null;
            if (cb != null) {
                cb.run();
            }
        }
    }

    private boolean isWriteTargetReady() {
        if (writeReadySupplier != null) {
            return writeReadySupplier.getAsBoolean();
        }
        if (writeChannel == null) {
            return true;
        }
        return writeChannel.isActive() && writeChannel.isWritable();
    }

    private EventExecutor ownerExecutor() {
        Channel loopOwner = pauseChannel != null ? pauseChannel : writeChannel;
        return loopOwner != null ? loopOwner.eventLoop() : null;
    }

    private void scheduleDrain(long waitNanos) {
        if (closed) {
            return;
        }
        if (timer != null && !timer.isDone()) {
            return;
        }
        timer = null;
        if (writeChannel != null && !writeChannel.isActive()) {
            close0();
            return;
        }
        // Keep draining on the owner EventLoop even after pauseChannel closes on EOF,
        // so deferred-EOF bytes queued for writeChannel finish pacing cleanly.
        EventExecutor executor = ownerExecutor();
        if (executor == null || executor.isShuttingDown()) {
            close0();
            return;
        }
        long clamped = Math.min(Math.max(1_000_000L, waitNanos), MAX_TIMER_SLICE_NANOS);
        timer = executor.schedule(drainTask, clamped, TimeUnit.NANOSECONDS);
    }

    private void cancelTimer() {
        ScheduledFuture<?> t = timer;
        if (t != null) {
            timer = null;
            t.cancel(false);
        }
    }

    private void pauseReadIfNeeded() {
        if (!readPaused && pauseChannel != null && pauseChannel.isOpen()) {
            readPaused = true;
            Sockets.pauseRead(pauseChannel, Sockets.READ_PAUSE_BANDWIDTH);
        }
    }

    private void resumeReadIfPaused() {
        if (readPaused) {
            readPaused = false;
            if (pauseChannel != null && pauseChannel.isOpen()) {
                Sockets.resumeRead(pauseChannel, Sockets.READ_PAUSE_BANDWIDTH);
            }
        }
    }

    private void registerPacer(BandwidthLimiter limiter) {
        if (!pacerRegistered && limiter != null) {
            pacerRegistered = true;
            reservedLimiter = limiter;
            limiter.pacerStarted(direction);
        }
    }

    private void unregisterPacer() {
        if (pacerRegistered && reservedLimiter != null) {
            pacerRegistered = false;
            reservedLimiter.pacerStopped(direction);
        }
    }

    private void releaseReserved(int bytes, int tasks) {
        BandwidthLimiter limiter = reservedLimiter;
        if (limiter == null) {
            return;
        }
        int relBytes = Math.min(bytes, reservedLimiterBytes);
        int relTasks = Math.min(tasks, reservedLimiterTasks);
        if (relBytes > 0 || relTasks > 0) {
            reservedLimiterBytes -= relBytes;
            reservedLimiterTasks -= relTasks;
            limiter.releasePendingTcp(relBytes, relTasks);
        }
    }

    private void releaseAllReserved() {
        BandwidthLimiter limiter = reservedLimiter;
        if (limiter != null && (reservedLimiterBytes > 0 || reservedLimiterTasks > 0)) {
            limiter.releasePendingTcp(reservedLimiterBytes, reservedLimiterTasks);
        }
        reservedLimiterBytes = 0;
        reservedLimiterTasks = 0;
        if (!pacerRegistered) {
            reservedLimiter = null;
        }
    }
}
