package org.rx.net;

import org.rx.diagnostic.DiagnosticMetrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Reusable, low-allocation scoped bidirectional bandwidth limiter using monotonic-clock token buckets.
 * Independent of Shadowsocks, RSS, Geo, or serialization layers so it can be attached to any user,
 * listener, upstream, or channel scope.
 */
public final class BandwidthLimiter {
    public enum Direction {
        UPLOAD,
        DOWNLOAD
    }

    public static final class Snapshot {
        public final String scope;
        public final long uploadBytesPerSecond;
        public final long downloadBytesPerSecond;
        public final long admittedUploadBytes;
        public final long admittedDownloadBytes;
        public final long droppedUploadPackets;
        public final long droppedDownloadPackets;
        public final long droppedUploadBytes;
        public final long droppedDownloadBytes;
        public final long delayedUploadEvents;
        public final long delayedDownloadEvents;
        public final long totalUploadDelayNanos;
        public final long totalDownloadDelayNanos;
        public final long pendingTcpBytes;
        public final int pendingTcpTasks;

        Snapshot(String scope, long uploadBytesPerSecond, long downloadBytesPerSecond,
                 long admittedUploadBytes, long admittedDownloadBytes,
                 long droppedUploadPackets, long droppedDownloadPackets,
                 long droppedUploadBytes, long droppedDownloadBytes,
                 long delayedUploadEvents, long delayedDownloadEvents,
                 long totalUploadDelayNanos, long totalDownloadDelayNanos,
                 long pendingTcpBytes, int pendingTcpTasks) {
            this.scope = scope;
            this.uploadBytesPerSecond = uploadBytesPerSecond;
            this.downloadBytesPerSecond = downloadBytesPerSecond;
            this.admittedUploadBytes = admittedUploadBytes;
            this.admittedDownloadBytes = admittedDownloadBytes;
            this.droppedUploadPackets = droppedUploadPackets;
            this.droppedDownloadPackets = droppedDownloadPackets;
            this.droppedUploadBytes = droppedUploadBytes;
            this.droppedDownloadBytes = droppedDownloadBytes;
            this.delayedUploadEvents = delayedUploadEvents;
            this.delayedDownloadEvents = delayedDownloadEvents;
            this.totalUploadDelayNanos = totalUploadDelayNanos;
            this.totalDownloadDelayNanos = totalDownloadDelayNanos;
            this.pendingTcpBytes = pendingTcpBytes;
            this.pendingTcpTasks = pendingTcpTasks;
        }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long SAFE_MUL_RATE_LIMIT = Long.MAX_VALUE / NANOS_PER_SECOND;
    private static final long MIN_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long METRICS_FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5L);

    static final long MIN_BURST_BYTES = 16L * 1024L;
    static final long MAX_BURST_BYTES = 2L * 1024L * 1024L;
    static final int MIN_TCP_CHUNK_BYTES = 1024;
    static final int MAX_TCP_CHUNK_BYTES = 16 * 1024;

    static final long DEFAULT_MAX_USER_PENDING_BYTES = Math.max(256L * 1024L,
            Long.getLong("rx.net.bandwidth.maxUserPendingBytes", 4L * 1024L * 1024L));
    static final int DEFAULT_MAX_USER_PENDING_TASKS = Math.max(64,
            Integer.getInteger("rx.net.bandwidth.maxUserPendingTasks", 1024));
    static final long DEFAULT_MAX_PROCESS_PENDING_BYTES = Math.max(1024L * 1024L,
            Long.getLong("rx.net.bandwidth.maxProcessPendingBytes", 32L * 1024L * 1024L));
    static final int DEFAULT_MAX_PROCESS_PENDING_TASKS = Math.max(256,
            Integer.getInteger("rx.net.bandwidth.maxProcessPendingTasks", 8192));

    private static final AtomicLong PROCESS_PENDING_BYTES = new AtomicLong();
    private static final AtomicInteger PROCESS_PENDING_TASKS = new AtomicInteger();

    private final String scope;
    private final LongSupplier clock;
    private final TokenBucket uploadBucket;
    private final TokenBucket downloadBucket;
    private final long maxUserPendingBytes;
    private final int maxUserPendingTasks;

    private final AtomicLong pendingTcpBytes = new AtomicLong();
    private final AtomicInteger pendingTcpTasks = new AtomicInteger();
    private final AtomicInteger activeUploadPacers = new AtomicInteger();
    private final AtomicInteger activeDownloadPacers = new AtomicInteger();
    private final AtomicLong lastMetricsFlushNanos = new AtomicLong();
    private final AtomicLong reportedUploadBytes = new AtomicLong();
    private final AtomicLong reportedDownloadBytes = new AtomicLong();
    private final AtomicLong reportedDroppedPackets = new AtomicLong();
    private final AtomicLong reportedDelayedEvents = new AtomicLong();
    private volatile BandwidthLimitConfig config;

    public BandwidthLimiter(String scope, BandwidthLimitConfig config) {
        this(scope, config, System::nanoTime, DEFAULT_MAX_USER_PENDING_BYTES, DEFAULT_MAX_USER_PENDING_TASKS);
    }

    public BandwidthLimiter(String scope, BandwidthLimitConfig config, LongSupplier clock) {
        this(scope, config, clock, DEFAULT_MAX_USER_PENDING_BYTES, DEFAULT_MAX_USER_PENDING_TASKS);
    }

    BandwidthLimiter(String scope, BandwidthLimitConfig config, LongSupplier clock,
                     long maxUserPendingBytes, int maxUserPendingTasks) {
        this.scope = scope == null || scope.isEmpty() ? "default" : scope;
        this.clock = clock == null ? System::nanoTime : clock;
        this.maxUserPendingBytes = Math.max(64L * 1024L, maxUserPendingBytes);
        this.maxUserPendingTasks = Math.max(16, maxUserPendingTasks);
        BandwidthLimitConfig initial = normalizeConfig(config);
        this.config = initial;
        long now = this.clock.getAsLong();
        this.uploadBucket = new TokenBucket(initial.uploadBytesPerSecond(), now);
        this.downloadBucket = new TokenBucket(initial.downloadBytesPerSecond(), now);
        this.lastMetricsFlushNanos.set(now);
    }

    public String scope() {
        return scope;
    }

    public BandwidthLimitConfig config() {
        return config.copy();
    }

    public boolean isEnabled() {
        return uploadBucket.rateBytesPerSecond > 0L || downloadBucket.rateBytesPerSecond > 0L;
    }

    public boolean isEnabled(Direction direction) {
        return bucket(direction).rateBytesPerSecond > 0L;
    }

    public long rateBytesPerSecond(Direction direction) {
        return bucket(direction).rateBytesPerSecond;
    }

    public long burstBytes(Direction direction) {
        return bucket(direction).burstCap;
    }

    /**
     * Dynamically updates bandwidth limits without granting extra burst credit.
     */
    public void configure(BandwidthLimitConfig nextConfig) {
        BandwidthLimitConfig normalized = normalizeConfig(nextConfig);
        long now = clock.getAsLong();
        uploadBucket.configure(normalized.uploadBytesPerSecond(), now);
        downloadBucket.configure(normalized.downloadBytesPerSecond(), now);
        this.config = normalized;
    }

    /**
     * Attempts to consume UDP payload bytes immediately. Returns false when over budget (caller drops datagram).
     */
    public boolean tryConsumeUdp(Direction direction, int bytes) {
        if (bytes <= 0) {
            return true;
        }
        TokenBucket bucket = bucket(direction);
        if (bucket.rateBytesPerSecond <= 0L) {
            return true;
        }
        long now = clock.getAsLong();
        boolean admitted = bucket.tryConsumeUdp(bytes, now);
        maybeFlushMetrics(now);
        return admitted;
    }

    /**
     * Attempts to consume TCP payload bytes immediately.
     * Returns 0 when admitted, or positive wait duration in nanoseconds when caller must pace.
     */
    public long tryConsumeTcp(Direction direction, int bytes) {
        if (bytes <= 0) {
            return 0L;
        }
        TokenBucket bucket = bucket(direction);
        if (bucket.rateBytesPerSecond <= 0L) {
            return 0L;
        }
        long now = clock.getAsLong();
        long waitNanos = bucket.tryConsumeTcp(bytes, now);
        maybeFlushMetrics(now);
        return waitNanos;
    }

    /**
     * Recommended slice size for TCP pacing so low-rate limits do not stall on oversized buffers.
     */
    public int recommendedTcpChunkBytes(Direction direction) {
        long rate = bucket(direction).rateBytesPerSecond;
        if (rate <= 0L) {
            return Integer.MAX_VALUE;
        }
        long target = rate / 16L;
        if (target < MIN_TCP_CHUNK_BYTES) {
            return MIN_TCP_CHUNK_BYTES;
        }
        if (target > MAX_TCP_CHUNK_BYTES) {
            return MAX_TCP_CHUNK_BYTES;
        }
        return (int) target;
    }

    /**
     * Per-turn drain quantum when multiple TCP connections share the same limiter, improving fairness.
     */
    int fairTurnQuantumBytes(Direction direction) {
        TokenBucket bucket = bucket(direction);
        long rate = bucket.rateBytesPerSecond;
        if (rate <= 0L) {
            return Integer.MAX_VALUE;
        }
        int active = activeTcpPacers(direction);
        if (active <= 1) {
            return Integer.MAX_VALUE;
        }
        long halfBurst = Math.max(MIN_TCP_CHUNK_BYTES, bucket.burstCap / 2L);
        long share = Math.max(recommendedTcpChunkBytes(direction), Math.min(32L * 1024L, halfBurst / active));
        return (int) Math.min(Integer.MAX_VALUE, share);
    }

    boolean reservePendingTcp(int bytes, int tasks) {
        if (bytes < 0 || tasks < 0) {
            return false;
        }
        if (bytes > 0 && !addBounded(pendingTcpBytes, bytes, maxUserPendingBytes)) {
            return false;
        }
        if (tasks > 0 && !addBoundedInt(pendingTcpTasks, tasks, maxUserPendingTasks)) {
            if (bytes > 0) {
                pendingTcpBytes.addAndGet(-bytes);
            }
            return false;
        }
        if (bytes > 0 && !addBounded(PROCESS_PENDING_BYTES, bytes, DEFAULT_MAX_PROCESS_PENDING_BYTES)) {
            pendingTcpBytes.addAndGet(-bytes);
            if (tasks > 0) {
                pendingTcpTasks.addAndGet(-tasks);
            }
            return false;
        }
        if (tasks > 0 && !addBoundedInt(PROCESS_PENDING_TASKS, tasks, DEFAULT_MAX_PROCESS_PENDING_TASKS)) {
            if (bytes > 0) {
                pendingTcpBytes.addAndGet(-bytes);
                PROCESS_PENDING_BYTES.addAndGet(-bytes);
            }
            pendingTcpTasks.addAndGet(-tasks);
            return false;
        }
        return true;
    }

    void releasePendingTcp(int bytes, int tasks) {
        if (bytes > 0) {
            pendingTcpBytes.addAndGet(-bytes);
            PROCESS_PENDING_BYTES.addAndGet(-bytes);
        }
        if (tasks > 0) {
            pendingTcpTasks.addAndGet(-tasks);
            PROCESS_PENDING_TASKS.addAndGet(-tasks);
        }
    }

    void recordTcpQueueOverflow(Direction direction, int bytes) {
        TokenBucket bucket = bucket(direction);
        bucket.droppedPackets.incrementAndGet();
        bucket.droppedBytes.addAndGet(Math.max(0, bytes));
    }

    void pacerStarted(Direction direction) {
        (direction == Direction.UPLOAD ? activeUploadPacers : activeDownloadPacers).incrementAndGet();
    }

    void pacerStopped(Direction direction) {
        (direction == Direction.UPLOAD ? activeUploadPacers : activeDownloadPacers).decrementAndGet();
    }

    int activeTcpPacers(Direction direction) {
        return (direction == Direction.UPLOAD ? activeUploadPacers : activeDownloadPacers).get();
    }

    public long pendingTcpBytes() {
        return pendingTcpBytes.get();
    }

    public int pendingTcpTasks() {
        return pendingTcpTasks.get();
    }

    public static long processPendingTcpBytes() {
        return PROCESS_PENDING_BYTES.get();
    }

    public static int processPendingTcpTasks() {
        return PROCESS_PENDING_TASKS.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                scope,
                uploadBucket.rateBytesPerSecond,
                downloadBucket.rateBytesPerSecond,
                uploadBucket.admittedBytes.get(),
                downloadBucket.admittedBytes.get(),
                uploadBucket.droppedPackets.get(),
                downloadBucket.droppedPackets.get(),
                uploadBucket.droppedBytes.get(),
                downloadBucket.droppedBytes.get(),
                uploadBucket.delayedEvents.get(),
                downloadBucket.delayedEvents.get(),
                uploadBucket.totalDelayNanos.get(),
                downloadBucket.totalDelayNanos.get(),
                pendingTcpBytes.get(),
                pendingTcpTasks.get()
        );
    }

    private TokenBucket bucket(Direction direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }
        return direction == Direction.UPLOAD ? uploadBucket : downloadBucket;
    }

    private void maybeFlushMetrics(long now) {
        if (!DiagnosticMetrics.isEnabled()) {
            return;
        }
        long last = lastMetricsFlushNanos.get();
        if (now - last < METRICS_FLUSH_INTERVAL_NANOS) {
            return;
        }
        if (!lastMetricsFlushNanos.compareAndSet(last, now)) {
            return;
        }
        long ulBytes = uploadBucket.admittedBytes.get();
        long dlBytes = downloadBucket.admittedBytes.get();
        long drops = uploadBucket.droppedPackets.get() + downloadBucket.droppedPackets.get();
        long delays = uploadBucket.delayedEvents.get() + downloadBucket.delayedEvents.get();

        long deltaUl = ulBytes - reportedUploadBytes.getAndSet(ulBytes);
        long deltaDl = dlBytes - reportedDownloadBytes.getAndSet(dlBytes);
        long deltaDrops = drops - reportedDroppedPackets.getAndSet(drops);
        long deltaDelays = delays - reportedDelayedEvents.getAndSet(delays);

        if (deltaUl > 0L) {
            DiagnosticMetrics.record("net.bandwidth.admitted.bytes", deltaUl, "dir=upload");
        }
        if (deltaDl > 0L) {
            DiagnosticMetrics.record("net.bandwidth.admitted.bytes", deltaDl, "dir=download");
        }
        if (deltaDrops > 0L) {
            DiagnosticMetrics.record("net.bandwidth.drop.count", deltaDrops);
        }
        if (deltaDelays > 0L) {
            DiagnosticMetrics.record("net.bandwidth.delay.count", deltaDelays);
        }
        long pending = pendingTcpBytes.get();
        if (pending > 0L) {
            DiagnosticMetrics.record("net.bandwidth.pending.tcp.bytes", pending);
        }
    }

    private static BandwidthLimitConfig normalizeConfig(BandwidthLimitConfig config) {
        if (config == null) {
            return BandwidthLimitConfig.unlimited();
        }
        config.validate();
        return config.copy();
    }

    static long computeBurstCap(long rateBytesPerSecond) {
        if (rateBytesPerSecond <= 0L) {
            return 0L;
        }
        long quarter = rateBytesPerSecond / 4L;
        if (quarter < MIN_BURST_BYTES) {
            return MIN_BURST_BYTES;
        }
        return Math.min(MAX_BURST_BYTES, quarter);
    }

    private static boolean addBounded(AtomicLong counter, long delta, long max) {
        for (;;) {
            long current = counter.get();
            if (delta > max - current) {
                return false;
            }
            if (counter.compareAndSet(current, current + delta)) {
                return true;
            }
        }
    }

    private static boolean addBoundedInt(AtomicInteger counter, int delta, int max) {
        for (;;) {
            int current = counter.get();
            if (delta > max - current) {
                return false;
            }
            if (counter.compareAndSet(current, current + delta)) {
                return true;
            }
        }
    }

    private static final class TokenBucket {
        volatile long rateBytesPerSecond;
        volatile long burstCap;
        long tokens;
        long nanoByteRemainder;
        long lastRefillNanos;
        boolean everConfiguredLimited;

        final AtomicLong admittedBytes = new AtomicLong();
        final AtomicLong droppedPackets = new AtomicLong();
        final AtomicLong droppedBytes = new AtomicLong();
        final AtomicLong delayedEvents = new AtomicLong();
        final AtomicLong totalDelayNanos = new AtomicLong();

        TokenBucket(long rateBytesPerSecond, long nowNanos) {
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.burstCap = computeBurstCap(rateBytesPerSecond);
            this.lastRefillNanos = nowNanos;
            if (rateBytesPerSecond > 0L) {
                this.tokens = this.burstCap;
                this.everConfiguredLimited = true;
            }
        }

        synchronized void configure(long newRateBytesPerSecond, long nowNanos) {
            long oldRate = this.rateBytesPerSecond;
            if (oldRate > 0L) {
                refillLocked(nowNanos);
            } else {
                this.lastRefillNanos = nowNanos;
                this.nanoByteRemainder = 0L;
            }
            long newBurst = computeBurstCap(newRateBytesPerSecond);
            this.rateBytesPerSecond = newRateBytesPerSecond;
            this.burstCap = newBurst;
            if (newRateBytesPerSecond <= 0L) {
                // Keep tokens capped at 0 when disabled so toggling off->on rapidly cannot manufacture extra burst.
                this.tokens = Math.min(this.tokens, 0L);
                this.nanoByteRemainder = 0L;
                return;
            }
            if (!everConfiguredLimited) {
                this.tokens = newBurst;
                this.everConfiguredLimited = true;
            } else if (oldRate <= 0L) {
                // Re-enabling after unlimited: allow at most a conservative initial burst without exceeding newBurst.
                this.tokens = Math.min(Math.max(this.tokens, MIN_BURST_BYTES), newBurst);
            } else {
                // Active rate change: never grant extra burst credit beyond current balance or new burst cap.
                if (this.tokens > newBurst) {
                    this.tokens = newBurst;
                    this.nanoByteRemainder = 0L;
                }
            }
        }

        synchronized boolean tryConsumeUdp(int bytes, long nowNanos) {
            long rate = this.rateBytesPerSecond;
            if (rate <= 0L) {
                return true;
            }
            refillLocked(nowNanos);
            if (tokens >= bytes || (bytes > burstCap && tokens >= 0L)) {
                tokens -= bytes;
                admittedBytes.addAndGet(bytes);
                return true;
            }
            droppedPackets.incrementAndGet();
            droppedBytes.addAndGet(bytes);
            return false;
        }

        synchronized long tryConsumeTcp(int bytes, long nowNanos) {
            long rate = this.rateBytesPerSecond;
            if (rate <= 0L) {
                return 0L;
            }
            refillLocked(nowNanos);
            if (tokens >= bytes || (bytes > burstCap && tokens >= 0L)) {
                tokens -= bytes;
                admittedBytes.addAndGet(bytes);
                return 0L;
            }
            long deficit = bytes > burstCap ? -tokens : (bytes - tokens);
            if (deficit <= 0L) {
                deficit = 1L;
            }
            long waitNanos = computeWaitNanos(deficit, rate);
            delayedEvents.incrementAndGet();
            totalDelayNanos.addAndGet(waitNanos);
            return waitNanos;
        }

        private void refillLocked(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0L) {
                return;
            }
            lastRefillNanos = nowNanos;
            long rate = this.rateBytesPerSecond;
            long cap = this.burstCap;
            if (rate <= 0L || tokens >= cap) {
                if (tokens >= cap) {
                    tokens = cap;
                    nanoByteRemainder = 0L;
                }
                return;
            }

            long wholeSec = elapsed / NANOS_PER_SECOND;
            long remNanos = elapsed % NANOS_PER_SECOND;
            long missing = cap - tokens;

            long addBytes = 0L;
            if (wholeSec > 0L) {
                if (rate >= missing || wholeSec > missing / rate) {
                    tokens = cap;
                    nanoByteRemainder = 0L;
                    return;
                }
                addBytes = wholeSec * rate;
            }

            if (remNanos > 0L) {
                long fracWhole;
                long fracRem;
                if (rate <= SAFE_MUL_RATE_LIMIT) {
                    long scaled = remNanos * rate;
                    fracWhole = scaled / NANOS_PER_SECOND;
                    fracRem = scaled % NANOS_PER_SECOND;
                } else {
                    long rateHigh = rate / NANOS_PER_SECOND;
                    long rateLow = rate % NANOS_PER_SECOND;
                    long lowScaled = remNanos * rateLow;
                    fracWhole = rateHigh * remNanos + (lowScaled / NANOS_PER_SECOND);
                    fracRem = lowScaled % NANOS_PER_SECOND;
                }
                long combinedRem = nanoByteRemainder + fracRem;
                if (combinedRem >= NANOS_PER_SECOND) {
                    fracWhole += combinedRem / NANOS_PER_SECOND;
                    combinedRem %= NANOS_PER_SECOND;
                }
                nanoByteRemainder = combinedRem;
                if (fracWhole >= missing - addBytes) {
                    tokens = cap;
                    nanoByteRemainder = 0L;
                    return;
                }
                addBytes += fracWhole;
            }

            tokens += addBytes;
            if (tokens >= cap) {
                tokens = cap;
                nanoByteRemainder = 0L;
            }
        }

        private static long computeWaitNanos(long deficitBytes, long rateBytesPerSecond) {
            if (deficitBytes <= 0L || rateBytesPerSecond <= 0L) {
                return MIN_WAIT_NANOS;
            }
            if (deficitBytes <= SAFE_MUL_RATE_LIMIT) {
                long nanos = (deficitBytes * NANOS_PER_SECOND + rateBytesPerSecond - 1L) / rateBytesPerSecond;
                return Math.max(MIN_WAIT_NANOS, nanos);
            }
            long wholeSec = deficitBytes / rateBytesPerSecond;
            long remBytes = deficitBytes % rateBytesPerSecond;
            long remNanos = (remBytes * NANOS_PER_SECOND + rateBytesPerSecond - 1L) / rateBytesPerSecond;
            if (wholeSec > (Long.MAX_VALUE - remNanos) / NANOS_PER_SECOND) {
                return Long.MAX_VALUE;
            }
            return Math.max(MIN_WAIT_NANOS, wholeSec * NANOS_PER_SECOND + remNanos);
        }
    }
}
