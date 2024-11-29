package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.rx.bean.*;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.BeanMapper;
import org.rx.util.Snowflake;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CpuWatchman implements TimerTask {
    @Getter
    @RequiredArgsConstructor
    public static class ThreadUsageView {
        final ThreadEntity begin;
        final ThreadEntity end;

        public long getCpuNanosElapsed() {
            if (end.cpuNanos == -1 || begin.cpuNanos == -1) {
                return -1;
            }
            return end.cpuNanos - begin.cpuNanos;
        }

        public long getUserNanosElapsed() {
            if (end.userNanos == -1 || begin.userNanos == -1) {
                return -1;
            }
            return end.userNanos - begin.userNanos;
        }

        public long getBlockedElapsed() {
            return end.blockedTime - begin.blockedTime;
        }

        public long getWaitedElapsed() {
            return end.waitedTime - begin.waitedTime;
        }

        @Override
        public String toString() {
            return String.format("begin: %s\nend: %s\ncpuNanosElapsed=%s, userNanosElapsed=%s, blockedElapsed=%s, waitedElapsed=%s", begin, end,
                    Sys.formatNanosElapsed(getCpuNanosElapsed()), Sys.formatNanosElapsed(getUserNanosElapsed()),
                    Sys.formatNanosElapsed(getBlockedElapsed()), Sys.formatNanosElapsed(getWaitedElapsed()));
        }
    }

    static final OperatingSystemMXBean osMx = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static final ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    //    static final HotspotThreadMBean internalThreadMx = ManagementFactoryHelper.getHotspotThreadMBean();
    static final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("timer", Thread.MAX_PRIORITY), 800L, TimeUnit.MILLISECONDS, 8);
    //place after timer
    static final CpuWatchman INSTANCE = new CpuWatchman();
    static Timeout samplingCpuTimeout;
    static long latestSnapshotId;

    public static synchronized Linq<ThreadEntity> getLatestSnapshot() {
        if (samplingCpuTimeout == null) {
            startWatch();
        }
        if (latestSnapshotId == 0) {
            return Linq.empty();
        }
        return TraceHandler.INSTANCE.queryThreadTrace(latestSnapshotId, null, null);
    }

    public static Linq<ThreadUsageView> findTopUsage(Date startTime, Date endTime) {
        return TraceHandler.INSTANCE.queryThreadTrace(null, startTime, endTime).groupBy(p -> p.threadId, (p, x) -> {
            if (x.count() <= 1) {
                return null;
            }
            ThreadEntity first = x.first();
            ThreadEntity last = x.last();
            return new ThreadUsageView(first, last);
        }).where(Objects::nonNull);
    }

    public static synchronized void startWatch() {
        if (samplingCpuTimeout != null) {
            samplingCpuTimeout.cancel();
        }
        threadMx.setThreadCpuTimeEnabled(true);
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        boolean watchLock = (conf.watchThreadFlags & 1) == 1;
        threadMx.setThreadContentionMonitoringEnabled(watchLock);
        samplingCpuTimeout = timer.newTimeout(t -> {
            try {
                TraceHandler.INSTANCE.saveThreadTrace(dumpAllThreads(true));
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log(e);
            } finally {
                t.timer().newTimeout(t.task(), RxConfig.INSTANCE.getTrace().getSamplingCpuPeriod(), TimeUnit.MILLISECONDS);
            }
        }, conf.samplingCpuPeriod, TimeUnit.MILLISECONDS);
    }

    public static void stopWatch() {
        if (samplingCpuTimeout != null) {
            samplingCpuTimeout.cancel();
            samplingCpuTimeout = null;
        }
        threadMx.setThreadCpuTimeEnabled(false);
        threadMx.setThreadContentionMonitoringEnabled(false);
    }

    public static synchronized Linq<ThreadEntity> dumpAllThreads(boolean findDeadlock) {
//        internalThreadMx.getInternalThreadCpuTimes()
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

    final Map<ThreadPoolExecutor, Tuple<IntWaterMark, int[]>> holder = Collections.synchronizedMap(new ReferenceIdentityMap<>(AbstractReferenceMap.ReferenceStrength.WEAK, AbstractReferenceMap.ReferenceStrength.HARD, 8, 0.75F));

    private CpuWatchman() {
        timer.newTimeout(this, RxConfig.INSTANCE.threadPool.samplingPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.threadPool;
        try {
            Decimal cpuLoad = Decimal.valueOf(conf.watchSystemCpu ? osMx.getSystemCpuLoad() : osMx.getProcessCpuLoad() * 100);
            for (Map.Entry<ThreadPoolExecutor, Tuple<IntWaterMark, int[]>> entry : holder.entrySet()) {
                ThreadPoolExecutor pool = entry.getKey();
                if (pool instanceof ScheduledExecutorService) {
                    scheduledThread(cpuLoad, pool, entry.getValue());
                    continue;
                }
                thread(cpuLoad, pool, entry.getValue());
            }
        } finally {
            timer.newTimeout(this, conf.samplingPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void thread(Decimal cpuLoad, ThreadPoolExecutor pool, Tuple<IntWaterMark, int[]> tuple) {
        IntWaterMark waterMark = tuple.left;
        int[] counter = tuple.right;
        int decrementCounter = counter[0];
        int incrementCounter = counter[1];

        String prefix = pool.toString();
        if (log.isDebugEnabled()) {
            log.debug("{} PoolSize={}/{}+[{}] Threshold={}[{}-{}]% de/incrementCounter={}/{}", prefix,
                    pool.getPoolSize(), pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrementCounter, incrementCounter);
        }

        if (cpuLoad.gt(waterMark.getHigh())) {
            if (++decrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                log.info("{} PoolSize={}/{}+[{}] Threshold={}[{}-{}]% decrement to {}", prefix,
                        pool.getPoolSize(), pool.getCorePoolSize(), pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), decrSize(pool));
                decrementCounter = 0;
            }
        } else {
            decrementCounter = 0;
        }

        if (!pool.getQueue().isEmpty() && cpuLoad.lt(waterMark.getLow())) {
            if (++incrementCounter >= RxConfig.INSTANCE.threadPool.samplingTimes) {
                log.info("{} PoolSize={}/{}+[{}] Threshold={}[{}-{}]% increment to {}", prefix,
                        pool.getPoolSize(), pool.getCorePoolSize(), pool.getQueue().size(),
                        cpuLoad, waterMark.getLow(), waterMark.getHigh(), incrSize(pool));
                incrementCounter = 0;
            }
        } else {
            incrementCounter = 0;
        }

        counter[0] = decrementCounter;
        counter[1] = incrementCounter;
    }

    private void scheduledThread(Decimal cpuLoad, ThreadPoolExecutor pool, Tuple<IntWaterMark, int[]> tuple) {
        IntWaterMark waterMark = tuple.left;
        int[] counter = tuple.right;
        int decrementCounter = counter[0];
        int incrementCounter = counter[1];

        String prefix = pool.toString();
        int active = pool.getActiveCount();
        int size = pool.getCorePoolSize();
        float idle = (float) active / size * 100;
        if (log.isDebugEnabled()) {
            log.debug("{} PoolSize={} QueueSize={} Threshold={}[{}-{}]% idle={} de/incrementCounter={}/{}", prefix,
                    pool.getCorePoolSize(), pool.getQueue().size(),
                    cpuLoad, waterMark.getLow(), waterMark.getHigh(), 100 - idle, decrementCounter, incrementCounter);
        }

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

        counter[0] = decrementCounter;
        counter[1] = incrementCounter;
    }

    public void register(@NonNull ThreadPoolExecutor pool, @NonNull IntWaterMark waterMark) {
        if (waterMark.getLow() < 0) {
            waterMark.setLow(0);
        }
        if (waterMark.getHigh() > 100) {
            waterMark.setHigh(100);
        }
        if (waterMark.getLow() > waterMark.getHigh()) {
            throw new InvalidException("waterMark low > high");
        }

        holder.put(pool, Tuple.of(waterMark, new int[2]));
    }

    public void unregister(@NonNull ThreadPoolExecutor pool) {
        holder.remove(pool);
    }
}
