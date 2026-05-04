package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.bean.Decimal;
import org.rx.bean.IntWaterMark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CpuWatchmanResizeTest {
    private ThreadPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
    }

    @Test
    void normalizeCpuLoadShouldSkipInvalidAndClampToPercent() {
        assertNull(CpuWatchman.INSTANCE.normalizeCpuLoad(Double.NaN));
        assertNull(CpuWatchman.INSTANCE.normalizeCpuLoad(-1D));

        Decimal load = CpuWatchman.INSTANCE.normalizeCpuLoad(1.50D);
        assertEquals(100D, load.doubleValue(), 0.001D);
    }

    @Test
    void cooldownShouldBlockPriorityResizePath() {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setMinIdleSize(1);
            conf.setMaxPoolSize(4);
            conf.setResizeStep(1);
            conf.setResizeCooldownMillis(60000L);
            pool = new ThreadPool(1, 8, new IntWaterMark(20, 80), "CPU-COOLDOWN");
            long[] state = CpuWatchman.INSTANCE.stateOf(pool);
            state[CpuWatchman.LAST_RESIZE_MILLIS] = System.currentTimeMillis();

            assertEquals(1, CpuWatchman.incrSize(pool));
            assertEquals(1, pool.getCorePoolSize());
            assertTrue(CpuWatchman.INSTANCE.isInCooldown(state, pool.getCorePoolSize(), "test"));
        }
    }

    @Test
    void resizeShouldStayWithinConfiguredBounds() {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setMinIdleSize(2);
            conf.setMaxPoolSize(3);
            conf.setResizeStep(10);
            conf.setResizeCooldownMillis(0L);
            pool = new ThreadPool(2, 8, new IntWaterMark(20, 80), "CPU-BOUNDS");

            assertEquals(3, CpuWatchman.incrSize(pool));
            assertEquals(3, CpuWatchman.incrSize(pool));
            assertEquals(2, CpuWatchman.decrSize(pool));
            assertEquals(2, CpuWatchman.decrSize(pool));
        }
    }

    @Test
    void registeredPoolShouldHaveResizeJitterState() {
        pool = ThreadPool.fixed("CPU-JITTER", 1, 4);
        long[] state = CpuWatchman.INSTANCE.stateOf(pool);

        assertEquals(CpuWatchman.STATE_SIZE, state.length);
        assertTrue(state[CpuWatchman.RESIZE_JITTER_MILLIS] >= 0L);
    }
}
