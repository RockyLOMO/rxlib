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
        assertEquals(0, queue.size());
        assertEquals(2, queue.remainingCapacity());
        blocking.countDown();
    }

    @Test
    void clearShouldReleaseQueueSlots() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        fillQueue(blocking);

        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();
        queue.clear();

        assertEquals(0, queue.size());
        assertEquals(2, queue.remainingCapacity());
        pool.execute(new Runnable() {
            @Override
            public void run() {
            }
        });
        assertEquals(1, queue.size());
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
        assertEquals(0, queue.size());
        assertEquals(2, queue.remainingCapacity());
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
        } finally {
            blocking.countDown();
        }
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

    private interface Condition {
        boolean ok();
    }
}
