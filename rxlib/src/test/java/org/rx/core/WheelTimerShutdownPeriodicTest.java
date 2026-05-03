package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WheelTimerShutdownPeriodicTest {
    private ThreadPool pool;
    private WheelTimer timer;

    @AfterEach
    void tearDown() {
        if (timer != null) {
            timer.shutdownNow();
        }
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownShouldCancelAnonymousLongDelayTask() throws Exception {
        pool = ThreadPool.fixed("TIMER-LONG", 1, 8);
        timer = new WheelTimer(pool);
        AtomicInteger counter = new AtomicInteger();
        ScheduledFuture<?> future = timer.schedule(new Runnable() {
            @Override
            public void run() {
                counter.incrementAndGet();
            }
        }, 5, TimeUnit.SECONDS);

        timer.shutdown();

        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, future::get);
        Thread.sleep(200);
        assertEquals(0, counter.get());
    }

    @Test
    void shutdownShouldRemoveTaskIdPeriodicHolder() throws Exception {
        pool = ThreadPool.fixed("TIMER-HOLDER", 1, 8);
        timer = new WheelTimer(pool);
        Object taskId = "period-holder";

        timer.setTimeout(new org.rx.util.function.Action() {
            @Override
            public void invoke() {
            }
        }, 5000, taskId, TimeoutFlag.PERIOD.flags());
        assertTrue(timer.getFutureById(taskId) != null);

        timer.shutdown();

        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
        assertNull(timer.getFutureById(taskId));
    }

    @Test
    void scheduleAtFixedRateShouldStopAfterShutdown() throws Exception {
        pool = ThreadPool.fixed("TIMER-FIXED-RATE", 1, 8);
        timer = new WheelTimer(pool);
        AtomicInteger counter = new AtomicInteger();
        timer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                counter.incrementAndGet();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        waitUntilAtLeast(counter, 1, 3000);

        timer.shutdown();
        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
        int afterShutdown = counter.get();
        Thread.sleep(200);

        assertEquals(afterShutdown, counter.get());
    }

    @Test
    void scheduleWithFixedDelayShouldStopAfterShutdown() throws Exception {
        pool = ThreadPool.fixed("TIMER-FIXED-DELAY", 1, 8);
        timer = new WheelTimer(pool);
        AtomicInteger counter = new AtomicInteger();
        timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                counter.incrementAndGet();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        waitUntilAtLeast(counter, 1, 3000);

        timer.shutdown();
        assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
        int afterShutdown = counter.get();
        Thread.sleep(200);

        assertEquals(afterShutdown, counter.get());
    }

    private void waitUntilAtLeast(AtomicInteger counter, int expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (counter.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(counter.get() >= expected);
    }
}
