package org.rx.core;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.BiTuple;
import org.rx.bean.Decimal;
import org.rx.bean.IntWaterMark;
import org.rx.bean.WeakIdentityMap;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CpuWatchman implements TimerTask {
    static final CpuWatchman INSTANCE = new CpuWatchman();
    static final OperatingSystemMXBean osMx = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
//    static final ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final HashedWheelTimer timer = new HashedWheelTimer(ThreadPool.newThreadFactory("timer"), 800L, TimeUnit.MILLISECONDS, 8);

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
