package org.rx.net.dns;

import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;
import org.rx.core.Tasks;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 directClient / remoteClient 的 volatile + DCL：多线程仅构造单例。
 */
class DnsClientSingletonTest {

    @BeforeEach
    @AfterEach
    void resetStatics() throws Exception {
        closeAndClear("directClient");
        closeAndClear("remoteClient");
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
    void directClient_manyThreads_sameInstance() throws Exception {
        int n = 64;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<DnsClient> first = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            Tasks.run(() -> {
                try {
                    start.await();
                    DnsClient c = DnsClient.directClient();
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

    @Test
    void nameServerProvider_emptyListRequiresExplicitLocalFallback() {
        assertThrows(InvalidException.class,
                () -> DnsClient.nameServerProvider(Collections.emptyList(), false));
        assertNotNull(DnsClient.nameServerProvider(Collections.emptyList(), true));
    }

    @Test
    void nameServerProvider_resolvesUnresolvedServerHostBeforeNettySanitize() {
        DnsServerAddressStreamProvider provider = DnsClient.nameServerProvider(
                Collections.singletonList(InetSocketAddress.createUnresolved("localhost", 53)), false);

        InetSocketAddress first = provider.nameServerAddressStream("example.com").next();
        assertNotNull(first.getAddress());
        assertEquals(53, first.getPort());
    }

    @Test
    void nameServerProvider_unresolvedServerHostHonorsLocalFallback() {
        InetSocketAddress invalid = InetSocketAddress.createUnresolved("bad host", 53);

        assertThrows(InvalidException.class,
                () -> DnsClient.nameServerProvider(Collections.singletonList(invalid), false));
        assertNotNull(DnsClient.nameServerProvider(Collections.singletonList(invalid), true));
    }
}
