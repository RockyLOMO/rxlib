package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.*;
import org.rx.exception.TraceHandler;
import org.rx.util.BeanMapper;
import org.rx.util.Snowflake;

import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CpuWatchman implements TimerTask {
    @RequiredArgsConstructor
    public static class TopUsageView {
        final ThreadEntity first;
        final ThreadEntity last;

        public long getCpuNanosElapsed() {
            if (last.cpuNanos == -1 || first.cpuNanos == -1) {
                return -1;
            }
            return last.cpuNanos - first.cpuNanos;
        }

        public long getUserNanosElapsed() {
            if (last.userNanos == -1 || first.userNanos == -1) {
                return -1;
            }
            return last.userNanos - first.userNanos;
        }
    }

    static final CpuWatchman INSTANCE = new CpuWatchman();
    static final OperatingSystemMXBean osMx = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static final ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("timer"), 800L, TimeUnit.MILLISECONDS, 8);
    static Timeout samplingThreadTimeout;
    @Getter
    static long latestSnapshotId;

    public static synchronized Linq<ThreadEntity> getLatestSnapshot() {
        if (samplingThreadTimeout == null) {
            startWatch();
        }
        if (latestSnapshotId == 0) {
            return Linq.empty();
        }
        return TraceHandler.INSTANCE.queryThreadTrace(latestSnapshotId, null, null);
    }

    public static Linq<TopUsageView> findTopUsage(Date startTime, Date endTime) {
        return TraceHandler.INSTANCE.queryThreadTrace(null, startTime, endTime).groupBy(p -> p.threadId, (p, x) -> {
            if (x.count() <= 1) {
                return null;
            }
            ThreadEntity first = x.first();
            ThreadEntity last = x.last();
            return new TopUsageView(first, last);
        }).where(Objects::nonNull);
    }

    public static synchronized void startWatch() {
        if (samplingThreadTimeout != null) {
            samplingThreadTimeout.cancel();
        }
        threadMx.setThreadCpuTimeEnabled(true);
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        boolean watchLock = (conf.watchThreadFlags & 1) == 1;
        threadMx.setThreadContentionMonitoringEnabled(watchLock);
        samplingThreadTimeout = timer.newTimeout(t -> {
            try {
                TraceHandler.INSTANCE.saveThreadTrace(dumpAllThreads(true));
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log(e);
            } finally {
                t.timer().newTimeout(t.task(), RxConfig.INSTANCE.getTrace().getSamplingThreadPeriod(), TimeUnit.MILLISECONDS);
            }
        }, conf.samplingThreadPeriod, TimeUnit.MILLISECONDS);
    }

    public static void stopWatch() {
        if (samplingThreadTimeout != null) {
            samplingThreadTimeout.cancel();
            samplingThreadTimeout = null;
        }
        threadMx.setThreadCpuTimeEnabled(false);
        threadMx.setThreadContentionMonitoringEnabled(false);
    }

    public static synchronized Linq<ThreadEntity> dumpAllThreads(boolean findDeadlock) {
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        boolean watchLock = (conf.watchThreadFlags & 1) == 1;
        boolean watchUserTime = (conf.watchThreadFlags & 2) == 2;

        Linq<ThreadEntity> allThreads = Linq.from(threadMx.dumpAllThreads(watchLock, watchLock)).select(p -> BeanMapper.DEFAULT.map(p, new ThreadEntity()));
        long[] deadlockedTids = findDeadlock ? Arrays.addAll(threadMx.findDeadlockedThreads(), threadMx.findMonitorDeadlockedThreads()) : null;
        DateTime st = DateTime.now();
        long[] tids = Arrays.toPrimitive(allThreads.select(ThreadEntity::getThreadId).toArray());
        long[] threadUserTime = watchUserTime ? threadMx.getThreadUserTime(tids) : null;
        long[] threadCpuTime = threadMx.getThreadCpuTime(tids);
        latestSnapshotId = Snowflake.DEFAULT.nextId();
        return allThreads.select((p, i) -> {
            p.setUserNanos(watchUserTime ? threadUserTime[i] : -1);
            p.setCpuNanos(threadCpuTime[i]);
            p.setDeadlocked(Arrays.contains(deadlockedTids, p.threadId));
            p.setSnapshotId(latestSnapshotId);
            p.setSnapshotTime(st);
            return p;
        });
    }

    static int incrSize(ThreadPoolExecutor pool) {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        int poolSize = pool.getCorePoolSize() + conf.resizeQuantity;
        if (poolSize > conf.maxDynamicSize) {
            return conf.maxDynamicSize;
        }
        pool.setCorePoolSize(poolSize);
        return poolSize;
    }

    static int decrSize(ThreadPoolExecutor pool) {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        int poolSize = Math.max(conf.minDynamicSize, pool.getCorePoolSize() - conf.resizeQuantity);
        pool.setCorePoolSize(poolSize);
        return poolSize;
    }

    final Map<ThreadPoolExecutor, BiTuple<IntWaterMark, Integer, Integer>> holder = new WeakIdentityMap<>(8);

    private CpuWatchman() {
        timer.newTimeout(this, RxConfig.INSTANCE.threadPool.samplingPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        try {
            Decimal cpuLoad = Decimal.valueOf(osMx.getSystemCpuLoad() * 100);
            for (Map.Entry<ThreadPoolExecutor, BiTuple<IntWaterMark, Integer, Integer>> entry : holder.entrySet()) {
                ThreadPoolExecutor pool = entry.getKey();
                if (pool instanceof ScheduledExecutorService) {
                    scheduledThread(cpuLoad, pool, entry.getValue());
                    continue;
                }
                thread(cpuLoad, pool, entry.getValue());
            }
        } finally {
            timer.newTimeout(this, RxConfig.INSTANCE.threadPool.samplingPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void thread(Decimal cpuLoad, ThreadPoolExecutor pool, BiTuple<IntWaterMark, Integer, Integer> tuple) {
        IntWaterMark waterMark = tuple.left;
        int decrementCounter = tuple.middle;
        int incrementCounter = tuple.right;

        String prefix = pool.toString();
        if (log.isDebugEnabled()) {
            log.debug("{} PoolSize={}+[{}] Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                    pool.getPoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);
        }

        if (cpuLoad.gt(waterMark.getHigh())) {
            if (++decrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                log.info("{} PoolSize={}+[{}] Threshold={}[{}-{}]% decrement to {}", prefix,
                        pool.getPoolSize(), pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrSize(pool));
                decrementCounter = 0;
            }
        } else {
            decrementCounter = 0;
        }

        if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
            if (++incrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                log.info("{} PoolSize={}+[{}] Threshold={}[{}-{}]% increment to {}", prefix,
                        pool.getPoolSize(), pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), incrSize(pool));
                incrementCounter = 0;
            }
        } else {
            incrementCounter = 0;
        }

        tuple.middle = decrementCounter;
        tuple.right = incrementCounter;
    }

    private void scheduledThread(Decimal cpuLoad, ThreadPoolExecutor pool, BiTuple<IntWaterMark, Integer, Integer> tuple) {
        IntWaterMark waterMark = tuple.left;
        int decrementCounter = tuple.middle;
        int incrementCounter = tuple.right;

        String prefix = pool.toString();
        int active = pool.getActiveCount();
        int size = pool.getCorePoolSize();
        float idle = (float) active / size * 100;
        log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% idle={} de/incrementCounter={}/{}", prefix,
                pool.getCorePoolSize(), pool.getQueue().size(),
                cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrementCounter, incrementCounter);

        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        if (size > conf.minDynamicSize && (idle <= waterMark.getHigh() || cpuLoad.gt(waterMark.getHigh()))) {
            if (++decrementCounter >= conf.samplingTimes) {
                log.info("{} Threshold={}[{}-{}]% idle={} decrement to {}", prefix,
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrSize(pool));
                decrementCounter = 0;
            }
        } else {
            decrementCounter = 0;
        }

        if (active >= size && cpuLoad.lt(waterMark.getLow())) {
            if (++incrementCounter >= conf.samplingTimes) {
                log.info("{} Threshold={}[{}-{}]% increment to {}", prefix,
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), incrSize(pool));
                incrementCounter = 0;
            }
        } else {
            incrementCounter = 0;
        }

        tuple.middle = decrementCounter;
        tuple.right = incrementCounter;
    }

    public void register(ThreadPoolExecutor pool, IntWaterMark cpuWaterMark) {
        if (cpuWaterMark == null) {
            return;
        }

        holder.put(pool, BiTuple.of(cpuWaterMark, 0, 0));
    }
}
