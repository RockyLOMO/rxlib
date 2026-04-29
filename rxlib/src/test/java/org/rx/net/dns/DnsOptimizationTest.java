package org.rx.net.dns;

import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.bean.RandomList;
import org.rx.core.cache.MemoryCache;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
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
    static class BrokenInterceptorCache extends MemoryCache<String, List<InetAddress>> {
        final AtomicInteger removeCalls = new AtomicInteger();

        @Override
        public List<InetAddress> get(Object key) {
            throw new IllegalArgumentException("decode failed");
        }

        @Override
        public List<InetAddress> remove(Object key) {
            removeCalls.incrementAndGet();
            return null;
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    static DefaultDnsResponse resolveOnce(DnsServer server, String host) throws Exception {
        DefaultDnsQuery query = new DefaultDnsQuery(1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(host, DnsRecordType.A));
        try {
            Promise<DefaultDnsResponse> promise = DnsResolveCore.resolve(server, server.upstreamClient,
                    InetAddress.getLoopbackAddress(), query, true, GlobalEventExecutor.INSTANCE);
            assertTrue(promise.await(5, TimeUnit.SECONDS), "DNS resolve promise timeout");
            assertTrue(promise.isSuccess(), () -> "DNS resolve failed: " + promise.cause());
            return promise.getNow();
        } finally {
            ReferenceCountUtil.release(query);
        }
    }

    @Test
    void invalidInterceptorCacheRead_isEvictedAndTreatedAsMiss() {
        BrokenInterceptorCache cache = new BrokenInterceptorCache();
        List<InetAddress> ips = DnsResolveCore.getCachedInterceptorIps(cache, "bad-key",
                InetAddress.getLoopbackAddress(), "broken.example");

        assertNull(ips);
        assertEquals(1, cache.removeCalls.get());
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
                Thread thread = new Thread(() -> {
                    try (DnsClient client = new DnsClient(Collections.singletonList(new InetSocketAddress("127.0.0.1", dnsPort)))) {
                        start.await();
                        results.add(client.resolve(host));
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }, "dns-optimization-test-" + i);
                thread.setDaemon(true);
                thread.start();
            }

            start.countDown();
            assertTrue(resolving.await(10, TimeUnit.SECONDS));
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

    @Test
    @Timeout(10)
    void interceptorDisconnect_triesNextResolverAndSkipsOpenBreaker() throws Exception {
        int dnsPort = freePort();
        String host1 = "failover-1-" + UUID.randomUUID() + ".example";
        String host2 = "failover-2-" + UUID.randomUUID() + ".example";
        InetAddress resolvedIp = InetAddress.getByName("198.51.100.89");
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        DnsServer.ResolveInterceptor failing = (srcIp, lookupHost) -> {
            failedCalls.incrementAndGet();
            throw new ClientDisconnectedException("dns-rpc-primary");
        };
        DnsServer.ResolveInterceptor healthy = (srcIp, lookupHost) -> {
            healthyCalls.incrementAndGet();
            return Collections.singletonList(resolvedIp);
        };
        RandomList<DnsServer.ResolveInterceptor> interceptors =
                new RandomList<DnsServer.ResolveInterceptor>(Arrays.asList(failing, healthy)) {
                    @Override
                    public DnsServer.ResolveInterceptor next() {
                        return failing;
                    }
                };
        DnsServer server = new DnsServer(dnsPort, Collections.emptyList());
        try {
            server.setInterceptors(interceptors);
            DefaultDnsResponse first = resolveOnce(server, host1);
            try {
                assertEquals(1, first.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(first);
            }

            assertEquals(1, failedCalls.get(), "首次失败后应打开短熔断");
            assertEquals(1, healthyCalls.get(), "应尝试下一个 DNS interceptor");
            assertFalse(server.isInterceptorAvailable(failing, System.currentTimeMillis()));

            DefaultDnsResponse second = resolveOnce(server, host2);
            try {
                assertEquals(1, second.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(second);
            }

            assertEquals(1, failedCalls.get(), "熔断窗口内不应再次调用失败 interceptor");
            assertEquals(2, healthyCalls.get());
        } finally {
            server.close();
        }
    }
}
