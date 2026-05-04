package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadPoolQueueShutdownTest {
    private ThreadPool pool;
    private ExecutorService submitter;

    @AfterEach
    void tearDown() {
        if (submitter != null) {
            submitter.shutdownNow();
        }
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
    }

    @Test
    void drainToShouldReleaseQueueSlots() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        fillQueue(blocking);

        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();
        List<Runnable> drained = new ArrayList<Runnable>();
        assertEquals(2, queue.drainTo(drained));

        assertEquals(2, drained.size());
        assertQueueInvariant(queue, 0);
        blocking.countDown();
    }

    @Test
    void clearShouldReleaseQueueSlots() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        fillQueue(blocking);

        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();
        queue.clear();

        assertQueueInvariant(queue, 0);
        pool.execute(new Runnable() {
            @Override
            public void run() {
            }
        });
        assertQueueInvariant(queue, 1);
        blocking.countDown();
    }

    @Test
    void shutdownNowShouldReleaseDrainedQueueSlotsAndUnregister() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        fillQueue(blocking);
        assertTrue(CpuWatchman.INSTANCE.holder.containsKey(pool));

        pool.shutdownNow();
        blocking.countDown();

        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();
        assertQueueInvariant(queue, 0);
        assertFalse(CpuWatchman.INSTANCE.holder.containsKey(pool));
    }

    @Test
    void shutdownShouldUnregisterStandalonePool() {
        pool = ThreadPool.fixed("STANDALONE-SHUTDOWN", 1, 4);
        assertTrue(CpuWatchman.INSTANCE.holder.containsKey(pool));

        pool.shutdown();

        assertFalse(CpuWatchman.INSTANCE.holder.containsKey(pool));
    }

    @Test
    void blockUntilSlotShouldRejectWhenPoolShutsDown() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            fillQueue(blocking);

            submitter = Executors.newSingleThreadExecutor();
            Future<Boolean> rejected = submitter.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    try {
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                            }
                        });
                        return false;
                    } catch (RejectedExecutionException e) {
                        return true;
                    }
                }
            });

            Thread.sleep(200);
            pool.shutdownNow();
            blocking.countDown();

            assertTrue(rejected.get(3, TimeUnit.SECONDS));
            assertQueueInvariant((ThreadPool.ThreadQueue) pool.getQueue(), 0);
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void callerRunsOverflowShouldNotLeakSlotsWhenShutdownRaces() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch callerRunning = new CountDownLatch(1);
        CountDownLatch releaseCaller = new CountDownLatch(1);
        AtomicInteger callerRuns = new AtomicInteger();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            fillQueue(blocking);

            submitter = Executors.newSingleThreadExecutor();
            Future<Boolean> overflow = submitter.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            callerRuns.incrementAndGet();
                            callerRunning.countDown();
                            try {
                                releaseCaller.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                    return true;
                }
            });

            assertTrue(callerRunning.await(3, TimeUnit.SECONDS));
            pool.shutdownNow();
            releaseCaller.countDown();
            blocking.countDown();

            assertTrue(overflow.get(3, TimeUnit.SECONDS));
            assertEquals(1, callerRuns.get());
            assertQueueInvariant((ThreadPool.ThreadQueue) pool.getQueue(), 0);
            assertTrue(pool.taskMap.isEmpty());
        } finally {
            releaseCaller.countDown();
            blocking.countDown();
        }
    }

    @Test
    void removeClearDrainShouldKeepCapacityInvariantWithConcurrentPoll() throws Exception {
        final ThreadPool.ThreadQueue queue = new ThreadPool.ThreadQueue(64);
        final List<Runnable> tasks = new ArrayList<Runnable>();
        for (int i = 0; i < 64; i++) {
            Runnable task = new Runnable() {
                @Override
                public void run() {
                }
            };
            tasks.add(task);
            assertTrue(queue.offer(task));
        }
        assertQueueInvariant(queue, 64);

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        submitter = Executors.newFixedThreadPool(4);
        List<Future<?>> workers = new ArrayList<Future<?>>();
        for (int i = 0; i < 4; i++) {
            workers.add(submitter.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(3, TimeUnit.SECONDS);
                        while (stop.getCount() > 0) {
                            queue.poll(10, TimeUnit.MILLISECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        }

        start.countDown();
        for (int i = 0; i < 24; i++) {
            queue.remove(tasks.get(i));
        }
        List<Runnable> drained = new ArrayList<Runnable>();
        queue.drainTo(drained, 24);
        queue.clear();
        stop.countDown();

        for (Future<?> worker : workers) {
            worker.get(3, TimeUnit.SECONDS);
        }
        assertQueueInvariant(queue, 0);
        for (int i = 0; i < 64; i++) {
            assertTrue(queue.offer(new Runnable() {
                @Override
                public void run() {
                }
            }));
        }
        assertQueueInvariant(queue, 64);
        queue.clear();
        assertQueueInvariant(queue, 0);
    }

    private void fillQueue(final CountDownLatch blocking) throws Exception {
        pool = ThreadPool.fixed("QUEUE-SHUTDOWN", 1, 2);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    blocking.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        waitUntil(new Condition() {
            @Override
            public boolean ok() {
                return pool.getActiveCount() == 1;
            }
        }, 3000);

        pool.execute(new Runnable() {
            @Override
            public void run() {
            }
        });
        pool.execute(new Runnable() {
            @Override
            public void run() {
            }
        });
        waitUntil(new Condition() {
            @Override
            public boolean ok() {
                return pool.getQueue().size() == 2;
            }
        }, 3000);
    }

    private void waitUntil(Condition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ok() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.ok());
    }

    private void assertQueueInvariant(ThreadPool.ThreadQueue queue, int expectedSize) {
        ThreadPool.ThreadQueue.CapacitySnapshot snapshot = queue.capacitySnapshot();
        assertEquals(expectedSize, snapshot.counter);
        assertEquals(expectedSize, queue.size());
        assertEquals(snapshot.remaining, queue.remainingCapacity());
        assertEquals(snapshot.remaining, snapshot.slots);
        assertEquals(snapshot.capacity, snapshot.counter + snapshot.slots);
        assertTrue(snapshot.counter >= 0);
        assertTrue(snapshot.slots >= 0);
    }

    private interface Condition {
        boolean ok();
    }
}
