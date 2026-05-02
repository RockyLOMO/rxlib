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
import org.rx.core.CachePolicy;
import org.rx.core.cache.MemoryCache;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
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

    static class RecordingInterceptorCache extends MemoryCache<String, List<InetAddress>> {
        final Map<String, CachePolicy> policies = new ConcurrentHashMap<String, CachePolicy>();

        @Override
        public List<InetAddress> put(String key, List<InetAddress> value, CachePolicy policy) {
            policies.put(key, policy);
            return super.put(key, value, policy);
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    static DefaultDnsResponse resolveOnce(DnsServer server, String host) throws Exception {
        return resolveOnce(server, host, DnsRecordType.A);
    }

    static DefaultDnsResponse resolveOnce(DnsServer server, String host, DnsRecordType type) throws Exception {
        DefaultDnsQuery query = new DefaultDnsQuery(1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(host, type));
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
    void normalizeDomainShouldReturnSameInstanceForLowercaseWithoutTrailingDot() {
        String input = "example.com";

        assertSame(input, DnsResolveCore.normalizeDomain(input));
    }

    @Test
    void normalizeDomainShouldTrimTrailingDot() {
        assertEquals("example.com", DnsResolveCore.normalizeDomain("example.com."));
    }

    @Test
    void normalizeDomainShouldAsciiLowercase() {
        assertEquals("example.com", DnsResolveCore.normalizeDomain("Example.COM."));
    }

    @Test
    void aAndAaaaShouldUseDifferentResolveKey() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String aKey = server.resolveKey("Example.COM.", DnsRecordType.A);
            String aaaaKey = server.resolveKey("example.com", DnsRecordType.AAAA);

            assertNotEquals(aKey, aaaaKey);
            assertEquals(aKey, server.resolveKey("example.com", DnsRecordType.A));
        } finally {
            server.close();
        }
    }

    @Test
    void aQueryShouldNotPolluteAaaaCacheWithIpv4OnlyResult() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String host = "ipv4-only-" + UUID.randomUUID() + ".example";
            InetAddress ipv4 = InetAddress.getByName("198.51.100.11");
            AtomicInteger calls = new AtomicInteger();
            server.setInterceptors(new RandomList<DnsServer.ResolveInterceptor>(Collections.singletonList((srcIp, lookupHost) -> {
                calls.incrementAndGet();
                return Collections.singletonList(ipv4);
            })));
            server.interceptorCache = new RecordingInterceptorCache();

            DefaultDnsResponse a = resolveOnce(server, host, DnsRecordType.A);
            try {
                assertEquals(1, a.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(a);
            }
            assertEquals(Collections.singletonList(ipv4), server.interceptorCache.get(server.cacheKey(host, DnsRecordType.A)));
            assertTrue(server.interceptorCache.get(server.cacheKey(host, DnsRecordType.AAAA)).isEmpty());

            DefaultDnsResponse aaaa = resolveOnce(server, host, DnsRecordType.AAAA);
            try {
                assertEquals(0, aaaa.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(aaaa);
            }
            assertEquals(1, calls.get(), "AAAA 命中空族类缓存后不应再次调用 interceptor");
        } finally {
            server.close();
        }
    }

    @Test
    void aaaaQueryShouldNotPolluteACacheWithIpv6OnlyResult() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String host = "ipv6-only-" + UUID.randomUUID() + ".example";
            InetAddress ipv6 = InetAddress.getByName("2001:db8::11");
            AtomicInteger calls = new AtomicInteger();
            server.setInterceptors(new RandomList<DnsServer.ResolveInterceptor>(Collections.singletonList((srcIp, lookupHost) -> {
                calls.incrementAndGet();
                return Collections.singletonList(ipv6);
            })));
            server.interceptorCache = new RecordingInterceptorCache();

            DefaultDnsResponse aaaa = resolveOnce(server, host, DnsRecordType.AAAA);
            try {
                assertEquals(1, aaaa.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(aaaa);
            }
            assertTrue(server.interceptorCache.get(server.cacheKey(host, DnsRecordType.A)).isEmpty());
            assertEquals(Collections.singletonList(ipv6), server.interceptorCache.get(server.cacheKey(host, DnsRecordType.AAAA)));

            DefaultDnsResponse a = resolveOnce(server, host, DnsRecordType.A);
            try {
                assertEquals(0, a.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(a);
            }
            assertEquals(1, calls.get(), "A 命中空族类缓存后不应再次调用 interceptor");
        } finally {
            server.close();
        }
    }

    @Test
    void mixedInterceptorResultShouldPopulateAAndAaaaSeparately() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String host = "mixed-" + UUID.randomUUID() + ".example";
            InetAddress ipv4 = InetAddress.getByName("198.51.100.12");
            InetAddress ipv6 = InetAddress.getByName("2001:db8::12");
            server.setInterceptors(new RandomList<DnsServer.ResolveInterceptor>(
                    Collections.singletonList((srcIp, lookupHost) -> Arrays.asList(ipv4, ipv6))));
            server.interceptorCache = new RecordingInterceptorCache();

            DefaultDnsResponse response = resolveOnce(server, host, DnsRecordType.A);
            try {
                assertEquals(1, response.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(response);
            }
            assertEquals(Collections.singletonList(ipv4), server.interceptorCache.get(server.cacheKey(host, DnsRecordType.A)));
            assertEquals(Collections.singletonList(ipv6), server.interceptorCache.get(server.cacheKey(host, DnsRecordType.AAAA)));
        } finally {
            server.close();
        }
    }

    @Test
    void emptyFamilyCacheShouldUseNegativeTtl() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String host = "negative-family-" + UUID.randomUUID() + ".example";
            InetAddress ipv4 = InetAddress.getByName("198.51.100.13");
            RecordingInterceptorCache cache = new RecordingInterceptorCache();
            server.setTtl(30);
            server.setNegativeTtl(1);
            server.setInterceptors(new RandomList<DnsServer.ResolveInterceptor>(
                    Collections.singletonList((srcIp, lookupHost) -> Collections.singletonList(ipv4))));
            server.interceptorCache = cache;

            DefaultDnsResponse response = resolveOnce(server, host, DnsRecordType.A);
            try {
                assertEquals(1, response.count(DnsSection.ANSWER));
            } finally {
                ReferenceCountUtil.release(response);
            }

            long aTtl = cache.policies.get(server.cacheKey(host, DnsRecordType.A)).ttl();
            long aaaaTtl = cache.policies.get(server.cacheKey(host, DnsRecordType.AAAA)).ttl();
            assertTrue(aTtl > 20_000L, "非空族类 cache 应使用正常 TTL");
            assertTrue(aaaaTtl > 0L && aaaaTtl <= 1_000L, "空族类 cache 应使用 negativeTtl");
        } finally {
            server.close();
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
            assertEquals(2, resolveCalls.get(), "并发相同域名请求应按 A/AAAA 各合并一次 resolveHost");

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
