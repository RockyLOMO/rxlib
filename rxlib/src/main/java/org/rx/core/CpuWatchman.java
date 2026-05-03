package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.rx.bean.Decimal;
import org.rx.bean.IntWaterMark;
import org.rx.bean.Tuple;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CpuWatchman implements TimerTask {
    static final OperatingSystemMXBean osMx = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("timer", Thread.MAX_PRIORITY), 800L, TimeUnit.MILLISECONDS, 8);
    //place after timer
    static final CpuWatchman INSTANCE = new CpuWatchman();

    static final int DECREMENT_COUNTER = 0;
    static final int INCREMENT_COUNTER = 1;
    static final int LAST_RESIZE_MILLIS = 2;

    static int incrSize(ThreadPoolExecutor pool) {
        long[] state = INSTANCE.stateOf(pool);
        int current = pool.getCorePoolSize();
        if (INSTANCE.isInCooldown(state, current, "priorityIncrement")) {
            return current;
        }
        ThreadPool threadPool = asThreadPool(pool);
        int maxPoolSize = threadPool != null ? threadPool.maxPoolSize() : RxConfig.INSTANCE.threadPool.maxPoolSize;
        int resizeStep = threadPool != null ? threadPool.resizeStep() : RxConfig.INSTANCE.threadPool.resizeStep;
        int poolSize = Math.min(maxPoolSize, current + resizeStep);
        if (poolSize != current) {
            pool.setCorePoolSize(poolSize);
            INSTANCE.markResize(state);
        }
        return poolSize;
    }

    static int decrSize(ThreadPoolExecutor pool) {
        long[] state = INSTANCE.stateOf(pool);
        int current = pool.getCorePoolSize();
        if (INSTANCE.isInCooldown(state, current, "priorityDecrement")) {
            return current;
        }
        ThreadPool threadPool = asThreadPool(pool);
        int minIdleSize = threadPool != null ? threadPool.minIdleSize() : RxConfig.INSTANCE.threadPool.minIdleSize;
        int resizeStep = threadPool != null ? threadPool.resizeStep() : RxConfig.INSTANCE.threadPool.resizeStep;
        int poolSize = Math.max(minIdleSize, current - resizeStep);
        if (poolSize != current) {
            pool.setCorePoolSize(poolSize);
            INSTANCE.markResize(state);
        }
        return poolSize;
    }

    private static ThreadPool asThreadPool(ThreadPoolExecutor pool) {
        return pool instanceof ThreadPool ? (ThreadPool) pool : null;
    }

    final Map<ThreadPoolExecutor, Tuple<IntWaterMark, long[]>> holder = Collections.synchronizedMap(new ReferenceIdentityMap<>(AbstractReferenceMap.ReferenceStrength.WEAK, AbstractReferenceMap.ReferenceStrength.HARD, 8, 0.75F));
    volatile boolean shutdown;

    private CpuWatchman() {
        timer.newTimeout(this, RxConfig.INSTANCE.threadPool.samplingPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        try {
            if (shutdown) {
                return;
            }
            double rawLoad = conf.watchSystemCpu ? osMx.getSystemCpuLoad() : osMx.getProcessCpuLoad();
            Decimal cpuLoad = normalizeCpuLoad(rawLoad);
            if (cpuLoad == null) {
                recordInvalidCpuLoad(rawLoad);
                return;
            }
            synchronized (holder) {
                for (Map.Entry<ThreadPoolExecutor, Tuple<IntWaterMark, long[]>> entry : holder.entrySet()) {
                    ThreadPoolExecutor pool = entry.getKey();
                    if (pool == null || pool.isShutdown()) {
                        continue;
                    }
                    if (pool instanceof ScheduledExecutorService) {
                        scheduledThread(cpuLoad, pool, entry.getValue());
                        continue;
                    }
                    thread(cpuLoad, pool, entry.getValue());
                }
            }
        } finally {
            if (!shutdown) {
                long period = Math.max(1L, conf.samplingPeriod);
                timer.newTimeout(this, period, TimeUnit.MILLISECONDS);
            }
        }
    }

    private Decimal normalizeCpuLoad(double rawLoad) {
        if (Double.isNaN(rawLoad) || rawLoad < 0D) {
            return null;
        }
        return Decimal.valueOf(Math.min(100D, rawLoad * 100D));
    }

    private void recordInvalidCpuLoad(double rawLoad) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("rx.thread_pool.cpu_load.invalid.count", 1D, "raw=" + rawLoad);
        }
    }

    private void thread(Decimal cpuLoad, ThreadPoolExecutor pool, Tuple<IntWaterMark, long[]> tuple) {
        ThreadPool threadPool = asThreadPool(pool);
        if (threadPool != null) {
            threadPool.recordDiagnosticMetrics();
        }
        IntWaterMark waterMark = tuple.left;
        long[] state = tuple.right;
        int decrementCounter = (int) state[DECREMENT_COUNTER];
        int incrementCounter = (int) state[INCREMENT_COUNTER];

        String prefix = pool.toString();
        if (log.isDebugEnabled()) {
            log.debug("{} PoolSize={}/{}+[{}] Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                    pool.getPoolSize(), pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);
        }

        int minIdleSize = threadPool != null ? threadPool.minIdleSize() : RxConfig.INSTANCE.threadPool.minIdleSize;
        if (pool.getCorePoolSize() > minIdleSize && cpuLoad.gt(waterMark.getHigh())) {
            if (++decrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                resize(pool, state, false, prefix, "cpuHigh", cpuLoad, waterMark);
                decrementCounter = 0;
            }
        } else {
            decrementCounter = 0;
        }

        if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
            if (++incrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                resize(pool, state, true, prefix, "queueBacklog", cpuLoad, waterMark);
                incrementCounter = 0;
            }
        } else {
            incrementCounter = 0;
        }

        state[DECREMENT_COUNTER] = decrementCounter;
        state[INCREMENT_COUNTER] = incrementCounter;
    }

    private void scheduledThread(Decimal cpuLoad, ThreadPoolExecutor pool, Tuple<IntWaterMark, long[]> tuple) {
        IntWaterMark waterMark = tuple.left;
        long[] state = tuple.right;
        int decrementCounter = (int) state[DECREMENT_COUNTER];
        int incrementCounter = (int) state[INCREMENT_COUNTER];

        String prefix = pool.toString();
        int active = pool.getActiveCount();
        int size = Math.max(1, pool.getCorePoolSize());
        float idle = (float) active / size * 100;
        if (log.isDebugEnabled()) {
            log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% idle={} de/incrementCounter={}/{}", prefix,
                    pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrementCounter, incrementCounter);
        }

        ThreadPool threadPool = asThreadPool(pool);
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        int minIdleSize = threadPool != null ? threadPool.minIdleSize() : RxConfig.INSTANCE.threadPool.minIdleSize;
        if (size > minIdleSize && (idle <= waterMark.getHigh() || cpuLoad.gt(waterMark.getHigh()))) {
            if (++decrementCounter >= conf.samplingTimes) {
                resize(pool, state, false, prefix, "scheduledIdle", cpuLoad, waterMark);
                decrementCounter = 0;
            }
        } else {
            decrementCounter = 0;
        }

        if (active >= size && cpuLoad.lt(waterMark.getLow())) {
            if (++incrementCounter >= conf.samplingTimes) {
                resize(pool, state, true, prefix, "scheduledBusy", cpuLoad, waterMark);
                incrementCounter = 0;
            }
        } else {
            incrementCounter = 0;
        }

        state[DECREMENT_COUNTER] = decrementCounter;
        state[INCREMENT_COUNTER] = incrementCounter;
    }

    private void resize(ThreadPoolExecutor pool, long[] state, boolean increment, String prefix, String reason, Decimal cpuLoad, IntWaterMark waterMark) {
        if (isInCooldown(state, pool.getCorePoolSize(), reason)) {
            return;
        }

        int before = pool.getCorePoolSize();
        try {
            int after = increment ? incrSize(pool) : decrSize(pool);
            markResize(state);
            recordResizeMetric(increment ? "increment" : "decrement", reason, before, after);
            log.info("{} Threshold={}[{}-{}]% {} to {}", prefix,
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), increment ? "increment" : "decrement", after);
        } catch (Throwable e) {
            recordResizeMetric("error", reason, before, before);
            log.warn("{} resize {} error", prefix, reason, e);
        }
    }

    private long[] stateOf(ThreadPoolExecutor pool) {
        synchronized (holder) {
            Tuple<IntWaterMark, long[]> tuple = holder.get(pool);
            return tuple == null ? null : tuple.right;
        }
    }

    private boolean isInCooldown(long[] state, int currentSize, String reason) {
        if (state == null) {
            return false;
        }
        long cooldown = Math.max(0L, RxConfig.INSTANCE.threadPool.resizeCooldownMillis);
        long lastResizeMillis = state[LAST_RESIZE_MILLIS];
        if (cooldown > 0L && lastResizeMillis > 0L && System.currentTimeMillis() - lastResizeMillis < cooldown) {
            recordResizeMetric("cooldown", reason, currentSize, currentSize);
            recordCooldownSkippedMetric(reason, currentSize);
            return true;
        }
        return false;
    }

    private void markResize(long[] state) {
        if (state != null) {
            state[LAST_RESIZE_MILLIS] = System.currentTimeMillis();
        }
    }

    private void recordResizeMetric(String action, String reason, int before, int after) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("rx.thread_pool.resize.count", 1D,
                    "action=" + action + ",reason=" + reason + ",before=" + before + ",after=" + after);
        }
    }

    private void recordCooldownSkippedMetric(String reason, int size) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("rx.thread_pool.resize.cooldown.skipped.count", 1D,
                    "reason=" + reason + ",size=" + size);
        }
    }

    public void register(@NonNull ThreadPoolExecutor pool, @NonNull IntWaterMark waterMark) {
        if (shutdown) {
            return;
        }
        if (asThreadPool(pool) == null) {
            pool.setCorePoolSize(RxConfig.INSTANCE.threadPool.minIdleSize);
        }
        if (waterMark.getLow() < 0) {
            waterMark.setLow(0);
        }
        if (waterMark.getHigh() > 100) {
            waterMark.setHigh(100);
        }
        if (waterMark.getLow() > waterMark.getHigh()) {
            throw new InvalidException("waterMark low > high");
        }

        holder.put(pool, Tuple.of(waterMark, new long[3]));
    }

    public void unregister(@NonNull ThreadPoolExecutor pool) {
        holder.remove(pool);
    }

    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        holder.clear();
        timer.stop();
    }
}
