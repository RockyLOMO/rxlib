package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.sleep;

@Slf4j
public class TasksTest extends AbstractTester {

    @Test
    public void testSchedulePeriod() {
        AtomicInteger counter = new AtomicInteger();
        long period = 400;
        ScheduledFuture<?> future = Tasks.schedulePeriod(() -> {
            int val = counter.incrementAndGet();
            log.info("Task executed: {}", val);
        }, period);

        try {
            sleep(period * 4 + 500); // ~2100ms
            int count = counter.get();
            log.info("Final count: {}", count);
            assertTrue(count >= 3, "Task should have executed at least 3 times, but got " + count);
        } finally {
            future.cancel(false);
        }
    }

    @Test
    public void testSchedulePeriodWithInitialDelay() {
        AtomicInteger counter = new AtomicInteger();
        long initialDelay = 1000;
        long period = 500;
        long start = System.currentTimeMillis();
        ScheduledFuture<?> future = Tasks.schedulePeriod(() -> {
            int val = counter.incrementAndGet();
            log.info("Task executed: {} at {}ms", val, System.currentTimeMillis() - start);
        }, initialDelay, period);

        try {
            sleep(initialDelay - 300); // 700ms
            assertTrue(counter.get() == 0, "Task should not have executed before initial delay");
            
            sleep(600); // Total 1300ms, should have executed once at 1000ms
            assertTrue(counter.get() == 1, "Task should have executed once after initial delay");

            sleep(period * 2 + 300); // Total ~2600ms, should have executed at 1000, 1500, 2000, 2500
            int count = counter.get();
            assertTrue(count >= 3, "Task should have executed at least 3 times, but got " + count);
        } finally {
            future.cancel(false);
        }
    }
}
