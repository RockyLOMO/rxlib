package org.rx.bean;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.core.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ConcurrentBlockingDequeTest {

    @Test
    public void testBasicOps() throws InterruptedException {
        ConcurrentBlockingDeque<Integer> q = new ConcurrentBlockingDeque<>();
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());

        q.addFirst(1);
        q.addLast(2);
        q.offerFirst(0);

        assertEquals(3, q.size());
        assertEquals(0, q.peekFirst());
        assertEquals(2, q.peekLast());

        assertEquals(0, q.takeFirst());
        assertEquals(1, q.takeFirst());
        assertEquals(2, q.takeFirst());

        assertTrue(q.isEmpty());
    }

    @Test
    public void testBlockingTake() throws InterruptedException {
        ConcurrentBlockingDeque<String> q = new ConcurrentBlockingDeque<>();
        long start = System.currentTimeMillis();

        new Thread(() -> {
            try {
                Thread.sleep(500);
                q.put("A");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        String s = q.take(); // Should block approx 500ms
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("A", s);
        assertTrue(elapsed >= 400, "Should block");
    }

    @Test
    public void testCapacity() throws InterruptedException {
        ConcurrentBlockingDeque<Integer> q = new ConcurrentBlockingDeque<>(2);
        q.put(1);
        q.put(2);

        assertFalse(q.offer(3)); // Full

        new Thread(() -> {
            try {
                Thread.sleep(500);
                q.take(); // Frees space
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        long start = System.currentTimeMillis();
        q.put(3); // Should block until take
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 400, "Should block on full capacity");
        assertEquals(2, q.size()); // 2, 3 remains
        assertEquals(2, q.poll());
        assertEquals(3, q.poll());
    }

    @Test
    public void testPollTimeout() throws InterruptedException {
        ConcurrentBlockingDeque<Integer> q = new ConcurrentBlockingDeque<>();
        long start = System.currentTimeMillis();
        Integer i = q.poll(500, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(i);
        assertTrue(elapsed >= 400, "Should wait for timeout");

        new Thread(() -> {
            try {
                Thread.sleep(200);
                q.put(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        start = System.currentTimeMillis();
        Integer j = q.poll(1000, TimeUnit.MILLISECONDS);
        elapsed = System.currentTimeMillis() - start;

        assertEquals(1, j);
        assertTrue(elapsed < 1000, "Should return immediately when available");
    }

    @Test
    public void testDrainTo() {
        ConcurrentBlockingDeque<Integer> q = new ConcurrentBlockingDeque<>();
        for (int i = 0; i < 10; i++) {
            q.add(i);
        }

        List<Integer> list = new ArrayList<>();
        q.drainTo(list);

        assertEquals(10, list.size());
        assertEquals(0, list.get(0));
        assertEquals(9, list.get(9));
        assertTrue(q.isEmpty());
    }

    @SneakyThrows
    @Test
    public void testConcurrency() {
        int threads = 10;
        int countPerThread = 1000;
        ConcurrentBlockingDeque<Integer> q = new ConcurrentBlockingDeque<>();
        AtomicInteger totalPut = new AtomicInteger();
        AtomicInteger totalTake = new AtomicInteger();

        CountDownLatch done = new CountDownLatch(threads * 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < countPerThread; j++) {
                        q.put(j);
                        totalPut.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });

            pool.submit(() -> {
                try {
                    for (int j = 0; j < countPerThread; j++) {
                        q.take();
                        totalTake.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        done.await(10, TimeUnit.SECONDS);
        assertEquals(threads * countPerThread, totalPut.get());
        assertEquals(threads * countPerThread, totalTake.get());
        assertTrue(q.isEmpty());
        pool.shutdown();
    }

    @Test
    public void testRemoveObj() throws InterruptedException {
        ConcurrentBlockingDeque<String> q = new ConcurrentBlockingDeque<>();
        q.put("A");
        q.put("B");
        q.put("C");

        assertTrue(q.remove("B"));
        assertEquals(2, q.size());

        assertEquals("A", q.take());
        assertEquals("C", q.take());
    }

    @Test
    public void testRemoveFirstLast() {
        ConcurrentBlockingDeque<String> q = new ConcurrentBlockingDeque<>();
        assertThrows(NoSuchElementException.class, q::removeFirst);
        assertThrows(NoSuchElementException.class, q::removeLast);

        q.add("A");
        assertEquals("A", q.removeFirst());
        assertTrue(q.isEmpty());
    }
}
