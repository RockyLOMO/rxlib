package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.DefaultAddressedEnvelope;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.bean.RandomList;
import org.rx.core.RxConfig;
import org.rx.core.CachePolicy;
import org.rx.core.cache.MemoryCache;
import org.rx.net.transport.ClientDisconnectedException;

import java.io.IOException;
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

    static class StubDnsClient extends DnsClient {
        final AtomicInteger queryCalls = new AtomicInteger();
        volatile String nextIp = "198.51.100.31";
        volatile int ttlSeconds = 30;
        volatile long delayMillis;
        volatile boolean fail;

        StubDnsClient() {
            super(Collections.emptyList(), true);
        }

        @Override
        public Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query(DnsQuestion question) {
            Promise<AddressedEnvelope<DnsResponse, InetSocketAddress>> promise = executor.newPromise();
            queryCalls.incrementAndGet();
            Runnable complete = () -> {
                if (fail) {
                    promise.tryFailure(new IOException("stub dns fail"));
                    return;
                }
                try {
                    DefaultDnsQuestion q = (DefaultDnsQuestion) question;
                    DefaultDnsResponse response = new DefaultDnsResponse(100,
                            io.netty.handler.codec.dns.DnsOpCode.QUERY, DnsResponseCode.NOERROR);
                    response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(q.name(), q.type(), q.dnsClass(),
                            ttlSeconds, Unpooled.wrappedBuffer(InetAddress.getByName(nextIp).getAddress())));
                    promise.trySuccess(new DefaultAddressedEnvelope<DnsResponse, InetSocketAddress>(response,
                            new InetSocketAddress("127.0.0.1", 53)));
                } catch (Throwable e) {
                    promise.tryFailure(e);
                }
            };
            if (delayMillis > 0) {
                executor.schedule(complete, delayMillis, TimeUnit.MILLISECONDS);
            } else {
                complete.run();
            }
            return promise;
        }
    }

    static class DnsCacheConfigState {
        final RxConfig.DnsCacheConfig cache = RxConfig.INSTANCE.getNet().getDns().getCache();
        final boolean prefetch = cache.isPrefetch();
        final int prefetchThresholdPercent = cache.getPrefetchThresholdPercent();
        final boolean serveExpired = cache.isServeExpired();
        final int serveExpiredTtlSeconds = cache.getServeExpiredTtlSeconds();
        final int serveExpiredReplyTtlSeconds = cache.getServeExpiredReplyTtlSeconds();
        final int serveExpiredClientTimeoutMillis = cache.getServeExpiredClientTimeoutMillis();
        final RxConfig.DnsCacheConfig.StorageMode storage = cache.getStorage();
        final int maximumSize = cache.getMaximumSize();
        final long maximumBytes = cache.getMaximumBytes();

        void configure(boolean prefetch, int prefetchThresholdPercent, boolean serveExpired,
                       int serveExpiredTtlSeconds, int serveExpiredReplyTtlSeconds,
                       int serveExpiredClientTimeoutMillis) {
            cache.setPrefetch(prefetch);
            cache.setPrefetchThresholdPercent(prefetchThresholdPercent);
            cache.setServeExpired(serveExpired);
            cache.setServeExpiredTtlSeconds(serveExpiredTtlSeconds);
            cache.setServeExpiredReplyTtlSeconds(serveExpiredReplyTtlSeconds);
            cache.setServeExpiredClientTimeoutMillis(serveExpiredClientTimeoutMillis);
            cache.setStorage(RxConfig.DnsCacheConfig.StorageMode.MEMORY);
            cache.setMaximumSize(64);
            cache.setMaximumBytes(65536);
            cache.normalize();
        }

        void restore() {
            cache.setPrefetch(prefetch);
            cache.setPrefetchThresholdPercent(prefetchThresholdPercent);
            cache.setServeExpired(serveExpired);
            cache.setServeExpiredTtlSeconds(serveExpiredTtlSeconds);
            cache.setServeExpiredReplyTtlSeconds(serveExpiredReplyTtlSeconds);
            cache.setServeExpiredClientTimeoutMillis(serveExpiredClientTimeoutMillis);
            cache.setStorage(storage);
            cache.setMaximumSize(maximumSize);
            cache.setMaximumBytes(maximumBytes);
            cache.normalize();
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

    static DefaultDnsResponse resolveOnce(DnsServer server, DnsClient upstream, String host,
                                          DnsRecordType type) throws Exception {
        DefaultDnsQuery query = new DefaultDnsQuery(1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(host, type));
        try {
            Promise<DefaultDnsResponse> promise = DnsResolveCore.resolve(server, upstream,
                    InetAddress.getLoopbackAddress(), query, true, GlobalEventExecutor.INSTANCE);
            assertTrue(promise.await(5, TimeUnit.SECONDS), "DNS resolve promise timeout");
            assertTrue(promise.isSuccess(), () -> "DNS resolve failed: " + promise.cause());
            return promise.getNow();
        } finally {
            ReferenceCountUtil.release(query);
        }
    }

    static InetAddress firstAnswerIp(DefaultDnsResponse response) throws Exception {
        DnsRawRecord record = response.recordAt(DnsSection.ANSWER, 0);
        byte[] bytes = new byte[record.content().readableBytes()];
        record.content().getBytes(record.content().readerIndex(), bytes);
        return InetAddress.getByAddress(bytes);
    }

    @Test
    void upstreamResponseCache_freshHitSkipsUpstreamQuery() throws Exception {
        DnsCacheConfigState state = new DnsCacheConfigState();
        state.configure(false, 10, true, 60, 15, 300);
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        StubDnsClient upstream = new StubDnsClient();
        try {
            String host = "upstream-cache-" + UUID.randomUUID() + ".example";
            InetAddress firstIp = InetAddress.getByName("198.51.100.31");
            upstream.nextIp = firstIp.getHostAddress();
            upstream.ttlSeconds = 30;

            DefaultDnsResponse first = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(firstIp, firstAnswerIp(first));
            } finally {
                ReferenceCountUtil.release(first);
            }

            upstream.nextIp = "198.51.100.32";
            DefaultDnsResponse second = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(firstIp, firstAnswerIp(second), "fresh cache 应返回首次上游结果");
            } finally {
                ReferenceCountUtil.release(second);
            }

            assertEquals(1, upstream.queryCalls.get(), "fresh cache 命中后不应再次访问上游");
            assertEquals(1, server.responseCacheFreshHits.get());
        } finally {
            upstream.close();
            server.close();
            state.restore();
        }
    }

    @Test
    @Timeout(10)
    void upstreamResponseCache_serveExpiredOnUpstreamTimeoutAndRefreshesCache() throws Exception {
        DnsCacheConfigState state = new DnsCacheConfigState();
        state.configure(false, 10, true, 60, 15, 50);
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        StubDnsClient upstream = new StubDnsClient();
        try {
            String host = "upstream-stale-" + UUID.randomUUID() + ".example";
            InetAddress oldIp = InetAddress.getByName("198.51.100.41");
            InetAddress newIp = InetAddress.getByName("198.51.100.42");
            upstream.nextIp = oldIp.getHostAddress();
            upstream.ttlSeconds = 1;

            DefaultDnsResponse first = resolveOnce(server, upstream, host, DnsRecordType.A);
            ReferenceCountUtil.release(first);
            Thread.sleep(1200);

            upstream.nextIp = newIp.getHostAddress();
            upstream.delayMillis = 300;
            DefaultDnsResponse stale = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(oldIp, firstAnswerIp(stale), "上游超时前应先返回 stale 记录");
                DnsRawRecord answer = stale.recordAt(DnsSection.ANSWER, 0);
                assertEquals(15, answer.timeToLive(), "stale 响应 TTL 应使用 serveExpiredReplyTtlSeconds");
            } finally {
                ReferenceCountUtil.release(stale);
            }

            Thread.sleep(500);
            upstream.delayMillis = 0;
            DefaultDnsResponse refreshed = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(newIp, firstAnswerIp(refreshed), "超时后的上游返回应刷新 stale cache");
            } finally {
                ReferenceCountUtil.release(refreshed);
            }

            assertEquals(2, upstream.queryCalls.get(), "第三次应命中刷新后的 cache");
            assertEquals(1, server.responseCacheUpstreamFailServedExpired.get());
        } finally {
            upstream.close();
            server.close();
            state.restore();
        }
    }

    @Test
    @Timeout(10)
    void upstreamResponseCache_prefetchUsesSingleRefreshTask() throws Exception {
        DnsCacheConfigState state = new DnsCacheConfigState();
        state.configure(true, 100, true, 60, 15, 300);
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        StubDnsClient upstream = new StubDnsClient();
        try {
            String host = "upstream-prefetch-" + UUID.randomUUID() + ".example";
            InetAddress oldIp = InetAddress.getByName("198.51.100.51");
            InetAddress newIp = InetAddress.getByName("198.51.100.52");
            upstream.nextIp = oldIp.getHostAddress();
            upstream.ttlSeconds = 30;

            DefaultDnsResponse first = resolveOnce(server, upstream, host, DnsRecordType.A);
            ReferenceCountUtil.release(first);

            upstream.nextIp = newIp.getHostAddress();
            upstream.delayMillis = 300;
            DefaultDnsResponse second = resolveOnce(server, upstream, host, DnsRecordType.A);
            DefaultDnsResponse third = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(oldIp, firstAnswerIp(second));
                assertEquals(oldIp, firstAnswerIp(third));
            } finally {
                ReferenceCountUtil.release(second);
                ReferenceCountUtil.release(third);
            }

            assertEquals(2, upstream.queryCalls.get(), "并发 prefetch key 应只启动一个后台刷新");
            assertEquals(1, server.responseCachePrefetchStarted.get());

            Thread.sleep(500);
            state.cache.setPrefetch(false);
            upstream.delayMillis = 0;
            DefaultDnsResponse refreshed = resolveOnce(server, upstream, host, DnsRecordType.A);
            try {
                assertEquals(newIp, firstAnswerIp(refreshed));
            } finally {
                ReferenceCountUtil.release(refreshed);
            }
            assertEquals(2, upstream.queryCalls.get(), "刷新完成后应直接命中 cache");
        } finally {
            upstream.close();
            server.close();
            state.restore();
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
    void domainCacheKeysShouldReuseHotKeyInstances() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            String aKey = server.cacheKey("Example.COM.", DnsRecordType.A);
            String aAgain = server.cacheKey("example.com", DnsRecordType.A);
            String resolveA = server.resolveKey("example.com", DnsRecordType.A);
            String responseA = server.responseCacheKey("example.com", DnsRecordType.A, io.netty.handler.codec.dns.DnsRecord.CLASS_IN);

            assertSame(aKey, aAgain);
            assertEquals("_dns:int:A:example.com", aKey);
            assertEquals("_dns:int:*:A:example.com", resolveA);
            assertEquals("_dns:rsp:A:1:example.com", responseA);
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
            server.setInterceptors(new RandomList<DnsResolveInterceptor>(Collections.singletonList((srcIp, lookupHost) -> {
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
            server.setInterceptors(new RandomList<DnsResolveInterceptor>(Collections.singletonList((srcIp, lookupHost) -> {
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
            server.setInterceptors(new RandomList<DnsResolveInterceptor>(
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
            server.setInterceptors(new RandomList<DnsResolveInterceptor>(
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
    @Timeout(60)
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
            Thread.sleep(800);

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
            assertTrue(resolving.await(35, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(2, resolveCalls.get(), "并发相同域名请求应按 A/AAAA 各合并一次 resolveHost");

            release.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
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
        DnsResolveInterceptor failing = (srcIp, lookupHost) -> {
            failedCalls.incrementAndGet();
            throw new ClientDisconnectedException("dns-rpc-primary");
        };
        DnsResolveInterceptor healthy = (srcIp, lookupHost) -> {
            healthyCalls.incrementAndGet();
            return Collections.singletonList(resolvedIp);
        };
        RandomList<DnsResolveInterceptor> interceptors =
                new RandomList<DnsResolveInterceptor>(Arrays.asList(failing, healthy)) {
                    @Override
                    public DnsResolveInterceptor next() {
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
