package org.rx.net.dns;

import org.junit.jupiter.api.Test;
import org.rx.bean.RandomList;
import org.rx.core.RxConfig;
import org.rx.core.cache.H2StoreCache;
import org.rx.core.cache.MemoryCache;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DnsClientLocalResolverTest {
    @Test
    void resolveAll_prefersLocalHostsBeforeUpstream() throws Exception {
        try (DnsClient client = new DnsClient(Collections.emptyList(), true)) {
            InetAddress ip = InetAddress.getByName("198.51.100.21");

            assertTrue(client.addHosts("client-hosts.example", "198.51.100.21"));

            assertEquals(Collections.singletonList(ip), client.resolveAll("client-hosts.example"));
            assertEquals(ip, client.resolve("client-hosts.example"));
        }
    }

    @Test
    void resolveAll_interceptorUsesCacheAndClearCache() throws Exception {
        try (DnsClient client = new DnsClient(Collections.emptyList(), true)) {
            String host = "client-interceptor-" + UUID.randomUUID() + ".example";
            InetAddress ip = InetAddress.getByName("198.51.100.22");
            AtomicInteger calls = new AtomicInteger();
            client.setInterceptors(new RandomList<DnsResolveInterceptor>(Collections.singletonList((srcIp, lookupHost) -> {
                if (!host.equals(lookupHost)) {
                    return null;
                }
                calls.incrementAndGet();
                return Collections.singletonList(ip);
            })));

            assertEquals(Collections.singletonList(ip), client.resolveAll(host));
            assertEquals(Collections.singletonList(ip), client.resolveAll(host));
            assertEquals(1, calls.get(), "DnsClient 本地 interceptor 应命中公共缓存");

            client.clearCache();
            assertEquals(Collections.singletonList(ip), client.resolveAll(host));
            assertEquals(2, calls.get(), "clearCache 应清理 DnsClient 公共 interceptor cache");
        }
    }

    @Test
    void interceptorCacheStorageConfig_selectsMemoryAndHybridStores() {
        RxConfig.DnsCacheConfig cache = RxConfig.INSTANCE.getNet().getDns().getCache();
        RxConfig.DnsCacheConfig.StorageMode oldStorage = cache.getStorage();
        int oldMaximumSize = cache.getMaximumSize();
        long oldMaximumBytes = cache.getMaximumBytes();
        try {
            cache.setStorage(RxConfig.DnsCacheConfig.StorageMode.MEMORY);
            cache.setMaximumSize(7);
            cache.setMaximumBytes(4096);
            try (DnsClient client = new DnsClient(Collections.emptyList(), true)) {
                client.setInterceptors(new RandomList<DnsResolveInterceptor>(Collections.singletonList((srcIp, host) -> {
                    try {
                        return Collections.singletonList(InetAddress.getByName("198.51.100.23"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })));
                assertTrue(client.interceptorCache instanceof MemoryCache);
            }

            cache.setStorage(RxConfig.DnsCacheConfig.StorageMode.HYBRID);
            cache.setMaximumSize(7);
            cache.setMaximumBytes(0);
            try (DnsClient client = new DnsClient(Collections.emptyList(), true)) {
                client.setInterceptors(new RandomList<DnsResolveInterceptor>(Collections.singletonList((srcIp, host) -> {
                    try {
                        return Collections.singletonList(InetAddress.getByName("198.51.100.24"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })));
                assertTrue(client.interceptorCache instanceof H2StoreCache);
            }
        } finally {
            cache.setStorage(oldStorage);
            cache.setMaximumSize(oldMaximumSize);
            cache.setMaximumBytes(oldMaximumBytes);
        }
    }
}
