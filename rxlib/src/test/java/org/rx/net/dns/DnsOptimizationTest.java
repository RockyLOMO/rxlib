package org.rx.net.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.bean.RandomList;
import org.rx.core.Tasks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DnsOptimizationTest {
    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    void getHosts_returnsCachedReadOnlySnapshotUntilMutation() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        InetAddress ip1 = InetAddress.getByName("127.0.0.1");
        InetAddress ip2 = InetAddress.getByName("127.0.0.2");
        try {
            server.setEnableHostsWeight(false);
            assertTrue(server.addHosts("snapshot.example", 1, java.util.Arrays.asList(ip1, ip2)));

            List<InetAddress> first = server.getHosts("snapshot.example");
            List<InetAddress> second = server.getHosts("snapshot.example");
            assertSame(first, second, "内容未变化时应复用只读快照");
            assertThrows(UnsupportedOperationException.class, () -> first.add(ip1));

            assertTrue(server.removeHosts("snapshot.example", Collections.singletonList(ip1)));
            List<InetAddress> third = server.getHosts("snapshot.example");
            assertNotSame(first, third, "内容变化后快照应失效重建");
            assertEquals(Collections.singletonList(ip2), third);
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void concurrentInterceptorQueries_shareSingleResolveTask() throws Exception {
        int dnsPort = freePort();
        String host = "coalesce-" + UUID.randomUUID() + ".example";
        InetAddress resolvedIp = InetAddress.getByName("198.51.100.88");
        AtomicInteger resolveCalls = new AtomicInteger();
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DnsServer server = new DnsServer(dnsPort, Collections.emptyList());
        try {
            server.setInterceptors(new RandomList<>(Collections.singletonList((srcIp, lookupHost) -> {
                if (!host.equals(lookupHost)) {
                    return null;
                }
                resolveCalls.incrementAndGet();
                resolving.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return Collections.singletonList(resolvedIp);
            })));
            Thread.sleep(300);

            int threads = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            CopyOnWriteArrayList<InetAddress> results = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threads; i++) {
                Tasks.run(() -> {
                    try (DnsClient client = new DnsClient(Collections.singletonList(new InetSocketAddress("127.0.0.1", dnsPort)))) {
                        start.await();
                        results.add(client.resolve(host));
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(resolving.await(3, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(1, resolveCalls.get(), "并发相同域名请求应只触发一次 resolveHost");

            release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNull(firstError.get(), () -> "并发 DNS 查询失败: " + firstError.get());
            assertEquals(threads, results.size());
            for (InetAddress result : results) {
                assertEquals(resolvedIp, result);
            }
        } finally {
            server.close();
        }
    }
}
