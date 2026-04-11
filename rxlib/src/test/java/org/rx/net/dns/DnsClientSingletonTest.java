package org.rx.net.dns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.core.Tasks;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 inlandClient / outlandClient 的 volatile + DCL：多线程仅构造单例。
 */
class DnsClientSingletonTest {

    @BeforeEach
    @AfterEach
    void resetStatics() throws Exception {
        closeAndClear("inlandClient");
        closeAndClear("outlandClient");
    }

    private static void closeAndClear(String fieldName) throws Exception {
        Field f = DnsClient.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        DnsClient c = (DnsClient) f.get(null);
        f.set(null, null);
        if (c != null && !c.isClosed()) {
            c.close();
        }
    }

    @Test
    void inlandClient_manyThreads_sameInstance() throws Exception {
        int n = 64;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<DnsClient> first = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            Tasks.run(() -> {
                try {
                    start.await();
                    DnsClient c = DnsClient.inlandClient();
                    first.compareAndSet(null, c);
                    assertSame(first.get(), c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertNotNull(first.get());
    }
}
