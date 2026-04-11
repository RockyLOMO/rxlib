package org.rx.bean;

import org.junit.jupiter.api.Test;
import org.rx.core.Tasks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 RandomList.addOrUpdate 与 next() 读锁快路径（高并发 DNS / 负载均衡场景）。
 */
class RandomListAddOrUpdateTest {

    @Test
    void addOrUpdate_insertsNewAndUpdatesWeightAtomically() {
        RandomList<String> list = new RandomList<>();
        assertTrue(list.addOrUpdate("a", 3));
        assertEquals(3, list.getWeight("a"));
        assertFalse(list.addOrUpdate("a", 3), "同权重应视为未新增");
        assertFalse(list.addOrUpdate("a", 5), "更新权重仍返回 false（非新元素）");
        assertEquals(5, list.getWeight("a"));
        assertEquals(1, list.size());
    }

    @Test
    void next_concurrentCalls_noLostElementsAndNoException() throws InterruptedException {
        RandomList<Integer> list = new RandomList<>();
        for (int i = 0; i < 8; i++) {
            list.add(Integer.valueOf(i), 1);
        }
        int threads = 16;
        int rounds = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Tasks.run(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        int v = list.next();
                        assertTrue(v >= 0 && v < 8, "next 应在有效范围内: " + v);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertEquals(0, errors.get(), "并发 next 不应抛错");
    }

    @Test
    void addOrUpdate_concurrentSameKey_singleElement() throws InterruptedException {
        RandomList<String> list = new RandomList<>();
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int w = t + 1;
            Tasks.run(() -> {
                try {
                    start.await();
                    list.addOrUpdate("k", w);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertEquals(1, list.size());
        int weight = list.getWeight("k");
        assertTrue(weight >= 1 && weight <= threads, "最终权重应为某次写入值: " + weight);
    }
}
