package org.rx.net.dns;

import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.core.cache.H2StoreCache;
import org.rx.core.cache.MemoryCache;
import org.rx.io.EntityDatabase;
import org.rx.io.Files;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
abstract class DnsResolverSupport extends Disposable {
    static final String DOMAIN_PREFIX = "_dns:";
    static final String INTERCEPTOR_PREFIX = "_dns:int:";
    static final String RESOLVE_PREFIX = "_dns:int:*:";
    static final String RESPONSE_PREFIX = "_dns:rsp:";
    static final String CACHE_TYPE_ALL = "ALL";
    static final long DEFAULT_INTERCEPTOR_BREAKER_MILLIS = 30_000L;

    static final class DomainCacheKeys {
        final String domain;
        volatile String domainKey;
        volatile String interceptorAllKey;
        volatile String interceptorAKey;
        volatile String interceptorAaaaKey;
        volatile String resolveAllKey;
        volatile String resolveAKey;
        volatile String resolveAaaaKey;
        volatile String responseInAllKey;
        volatile String responseInAKey;
        volatile String responseInAaaaKey;

        DomainCacheKeys(String domain) {
            this.domain = domain;
        }

        String domainKey() {
            String key = domainKey;
            if (key == null) {
                key = buildDomainKey(domain);
                domainKey = key;
            }
            return key;
        }

        String interceptorKey(DnsRecordType queryType) {
            if (queryType == DnsRecordType.A) {
                String key = interceptorAKey;
                if (key == null) {
                    key = buildTypedKey(INTERCEPTOR_PREFIX, DnsRecordType.A.name(), domain);
                    interceptorAKey = key;
                }
                return key;
            }
            if (queryType == DnsRecordType.AAAA) {
                String key = interceptorAaaaKey;
                if (key == null) {
                    key = buildTypedKey(INTERCEPTOR_PREFIX, DnsRecordType.AAAA.name(), domain);
                    interceptorAaaaKey = key;
                }
                return key;
            }
            if (queryType == null) {
                String key = interceptorAllKey;
                if (key == null) {
                    key = buildTypedKey(INTERCEPTOR_PREFIX, CACHE_TYPE_ALL, domain);
                    interceptorAllKey = key;
                }
                return key;
            }
            return buildTypedKey(INTERCEPTOR_PREFIX, queryType.name(), domain);
        }

        String resolveKey(DnsRecordType queryType) {
            if (queryType == DnsRecordType.A) {
                String key = resolveAKey;
                if (key == null) {
                    key = buildTypedKey(RESOLVE_PREFIX, DnsRecordType.A.name(), domain);
                    resolveAKey = key;
                }
                return key;
            }
            if (queryType == DnsRecordType.AAAA) {
                String key = resolveAaaaKey;
                if (key == null) {
                    key = buildTypedKey(RESOLVE_PREFIX, DnsRecordType.AAAA.name(), domain);
                    resolveAaaaKey = key;
                }
                return key;
            }
            if (queryType == null) {
                String key = resolveAllKey;
                if (key == null) {
                    key = buildTypedKey(RESOLVE_PREFIX, CACHE_TYPE_ALL, domain);
                    resolveAllKey = key;
                }
                return key;
            }
            return buildTypedKey(RESOLVE_PREFIX, queryType.name(), domain);
        }

        String responseKey(DnsRecordType queryType, int dnsClass) {
            if (dnsClass == DnsRecord.CLASS_IN) {
                if (queryType == DnsRecordType.A) {
                    String key = responseInAKey;
                    if (key == null) {
                        key = buildResponseKey(DnsRecordType.A.name(), dnsClass, domain);
                        responseInAKey = key;
                    }
                    return key;
                }
                if (queryType == DnsRecordType.AAAA) {
                    String key = responseInAaaaKey;
                    if (key == null) {
                        key = buildResponseKey(DnsRecordType.AAAA.name(), dnsClass, domain);
                        responseInAaaaKey = key;
                    }
                    return key;
                }
                if (queryType == null) {
                    String key = responseInAllKey;
                    if (key == null) {
                        key = buildResponseKey(CACHE_TYPE_ALL, dnsClass, domain);
                        responseInAllKey = key;
                    }
                    return key;
                }
            }
            return buildResponseKey(queryType != null ? queryType.name() : CACHE_TYPE_ALL, dnsClass, domain);
        }
    }

    @Setter
    int ttl = 1800;
    @Setter
    int hostsTtl = 180;
    @Setter
    boolean enableHostsWeight;
    @Getter
    final Map<String, RandomList<InetAddress>> hosts = new ConcurrentHashMap<>();
    @Getter
    @Setter
    int negativeTtl = DnsServer.DEFAULT_NEGATIVE_TTL;
    RandomList<DnsResolveInterceptor> interceptors;
    Cache<String, List<InetAddress>> interceptorCache;
    Cache<String, DnsResponseCacheEntry> responseCache;
    final Cache<String, DomainCacheKeys> domainKeyCache = new MemoryCache<>(b -> b.maximumSize(4096));
    final Map<String, Promise<List<InetAddress>>> resolvingPromises = new ConcurrentHashMap<>();
    final Map<String, Promise<DnsResponseCacheEntry>> responseRefreshPromises = new ConcurrentHashMap<>();
    final Map<DnsResolveInterceptor, Long> interceptorBreakerUntil = new ConcurrentHashMap<>();
    final AtomicLong responseCacheFreshHits = new AtomicLong();
    final AtomicLong responseCacheStaleHits = new AtomicLong();
    final AtomicLong responseCacheMisses = new AtomicLong();
    final AtomicLong responseCachePrefetchStarted = new AtomicLong();
    final AtomicLong responseCachePrefetchSuccess = new AtomicLong();
    final AtomicLong responseCachePrefetchFailure = new AtomicLong();
    final AtomicLong responseCacheUpstreamFailServedExpired = new AtomicLong();
    @Setter
    long interceptorBreakerOpenMillis = DEFAULT_INTERCEPTOR_BREAKER_MILLIS;

    public void setInterceptors(RandomList<DnsResolveInterceptor> interceptors) {
        if (CollectionUtils.isEmpty(interceptors)) {
            this.interceptors = null;
            interceptorCache = null;
            interceptorBreakerUntil.clear();
            return;
        }

        interceptorCache = newInterceptorCache();
        this.interceptors = interceptors;
        retainInterceptorBreakerKeys(interceptors.aliveList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Cache<String, List<InetAddress>> newInterceptorCache() {
        RxConfig.DnsCacheConfig config = RxConfig.INSTANCE.getNet().getDns().getCache();
        config.normalize();
        switch (config.getStorage()) {
            case MEMORY:
                return newMemoryInterceptorCache(config);
            case PERSISTENT:
                return (Cache) new H2StoreCache<String, List<InetAddress>>(
                        EntityDatabase.DEFAULT, 1L, 1);
            case HYBRID:
            default:
                return (Cache) new H2StoreCache<String, List<InetAddress>>(
                        EntityDatabase.DEFAULT, config.getMaximumSize(), 2);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Cache<String, DnsResponseCacheEntry> newResponseCache() {
        RxConfig.DnsCacheConfig config = RxConfig.INSTANCE.getNet().getDns().getCache();
        config.normalize();
        switch (config.getStorage()) {
            case MEMORY:
                return newMemoryResponseCache(config);
            case PERSISTENT:
                return (Cache) new H2StoreCache<String, DnsResponseCacheEntry>(
                        EntityDatabase.DEFAULT, 1L, 1);
            case HYBRID:
            default:
                return (Cache) new H2StoreCache<String, DnsResponseCacheEntry>(
                        EntityDatabase.DEFAULT, config.getMaximumSize(), 2);
        }
    }

    MemoryCache<String, List<InetAddress>> newMemoryInterceptorCache(RxConfig.DnsCacheConfig config) {
        final long maximumBytes = config.getMaximumBytes();
        if (maximumBytes > 0) {
            return new MemoryCache<>(
                    b -> b.maximumWeight(maximumBytes).weigher(DnsResolverSupport::estimateInetAddressListBytes));
        }
        return new MemoryCache<>(b -> b.maximumSize(config.getMaximumSize()));
    }

    MemoryCache<String, DnsResponseCacheEntry> newMemoryResponseCache(RxConfig.DnsCacheConfig config) {
        final long maximumBytes = config.getMaximumBytes();
        if (maximumBytes > 0) {
            return new MemoryCache<>(
                    b -> b.maximumWeight(maximumBytes).weigher(DnsResolverSupport::estimateDnsResponseEntryBytes));
        }
        return new MemoryCache<>(b -> b.maximumSize(config.getMaximumSize()));
    }

    static int estimateInetAddressListBytes(String key, List<InetAddress> value) {
        long bytes = 64L;
        if (key != null) {
            bytes += 40L + ((long) key.length() << 1);
        }
        if (value != null) {
            bytes += 24L + ((long) value.size() << 3);
            for (int i = 0; i < value.size(); i++) {
                InetAddress address = value.get(i);
                byte[] raw = address != null ? address.getAddress() : null;
                bytes += 32L + (raw == null ? 0 : raw.length);
            }
        }
        if (bytes <= 0) {
            return 1;
        }
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    static int estimateDnsResponseEntryBytes(String key, DnsResponseCacheEntry value) {
        if (value == null) {
            return 1;
        }
        int bytes = value.sizeInBytes(key);
        return bytes <= 0 ? 1 : bytes;
    }

    boolean isInterceptorAvailable(DnsResolveInterceptor interceptor, long nowMillis) {
        Long until = interceptorBreakerUntil.get(interceptor);
        if (until == null) {
            return true;
        }
        if (until <= nowMillis) {
            interceptorBreakerUntil.remove(interceptor, until);
            return true;
        }
        return false;
    }

    void markInterceptorSuccess(DnsResolveInterceptor interceptor) {
        interceptorBreakerUntil.remove(interceptor);
    }

    void markInterceptorFailure(DnsResolveInterceptor interceptor) {
        long openMillis = interceptorBreakerOpenMillis;
        if (openMillis <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + openMillis;
        interceptorBreakerUntil.put(interceptor, until < 0 ? Long.MAX_VALUE : until);
    }

    void retainInterceptorBreakerKeys(Collection<DnsResolveInterceptor> aliveInterceptors) {
        if (!interceptorBreakerUntil.isEmpty()) {
            interceptorBreakerUntil.keySet().retainAll(aliveInterceptors);
        }
    }

    public List<InetAddress> getHosts(String host) {
        host = normalizeHost(host);
        RandomList<InetAddress> ips = hosts.get(host);
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        if (ips.size() == 1) {
            return Collections.singletonList(ips.get(0));
        }
        return enableHostsWeight ? weightedHosts(ips) : ips.readOnlySnapshot();
    }

    public List<InetAddress> getAllHosts(String host) {
        host = normalizeHost(host);
        RandomList<InetAddress> ips = hosts.get(host);
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ips);
    }

    String cacheKey(String domain) {
        return domainKeys(domain).domainKey();
    }

    String cacheKey(String domain, DnsRecordType queryType) {
        return domainKeys(domain).interceptorKey(queryType);
    }

    String resolveKey(String domain, DnsRecordType queryType) {
        return domainKeys(domain).resolveKey(queryType);
    }

    String responseCacheKey(String domain, DnsRecordType queryType, int dnsClass) {
        return domainKeys(domain).responseKey(queryType, dnsClass);
    }

    String responseCacheKey(DefaultDnsQuestion question) {
        return responseCacheKey(question.name(), question.type(), question.dnsClass());
    }

    String normalizeHost(String host) {
        return DnsResolveCore.normalizeDomain(host);
    }

    DomainCacheKeys domainKeys(String domain) {
        final String normalized = normalizeHost(domain);
        return domainKeyCache.get(normalized, k -> new DomainCacheKeys(k));
    }

    static String buildDomainKey(String domain) {
        return new StringBuilder(DOMAIN_PREFIX.length() + domain.length())
                .append(DOMAIN_PREFIX)
                .append(domain)
                .toString();
    }

    static String buildTypedKey(String prefix, String typeName, String domain) {
        return new StringBuilder(prefix.length() + typeName.length() + 1 + domain.length())
                .append(prefix)
                .append(typeName)
                .append(':')
                .append(domain)
                .toString();
    }

    static String buildResponseKey(String typeName, int dnsClass, String domain) {
        return new StringBuilder(RESPONSE_PREFIX.length() + typeName.length() + 12 + domain.length())
                .append(RESPONSE_PREFIX)
                .append(typeName)
                .append(':')
                .append(dnsClass)
                .append(':')
                .append(domain)
                .toString();
    }

    Future<List<InetAddress>> resolveLocalAllAsync(InetAddress srcIp, String host, DnsRecordType queryType,
                                                   EventExecutor executor) {
        String domain = normalizeHost(host);
        List<InetAddress> hIps = DnsResolveCore.filterByType(getHosts(domain), queryType);
        if (!hIps.isEmpty()) {
            return executor.newSucceededFuture(hIps);
        }

        RandomList<DnsResolveInterceptor> localInterceptors = interceptors;
        if (localInterceptors == null || domain.endsWith(".lan")
                || (queryType != null && queryType != DnsRecordType.A && queryType != DnsRecordType.AAAA)) {
            return null;
        }

        List<InetAddress> ips = cachedLocalIps(srcIp, domain, queryType);
        if (ips != null) {
            return executor.newSucceededFuture(DnsResolveCore.filterByType(ips, queryType));
        }

        String resolveKey = resolveKey(domain, queryType);
        Promise<List<InetAddress>> newResolve = executor.newPromise();
        Promise<List<InetAddress>> resolvePromise = resolvingPromises.putIfAbsent(resolveKey, newResolve);
        if (resolvePromise == null) {
            resolvePromise = newResolve;
            Promise<List<InetAddress>> targetPromise = resolvePromise;
            Tasks.run(() -> {
                try {
                    List<InetAddress> resolvedIps =
                            DnsResolveCore.resolveByInterceptorWithFailover(this, localInterceptors, srcIp, domain);
                    if (resolvedIps != null) {
                        cacheInterceptorResult(domain, queryType, resolvedIps);
                    }
                    targetPromise.trySuccess(resolvedIps == null
                            ? null : DnsResolveCore.filterByType(resolvedIps, queryType));
                } catch (Throwable e) {
                    DnsResolveCore.logResolveFailure(srcIp, domain, e, false);
                    targetPromise.tryFailure(e);
                } finally {
                    resolvingPromises.remove(resolveKey, targetPromise);
                }
            });
        }
        return resolvePromise;
    }

    List<InetAddress> cachedLocalIps(InetAddress srcIp, String domain, DnsRecordType queryType) {
        if (interceptorCache == null) {
            return null;
        }
        List<InetAddress> ips = DnsResolveCore.getCachedInterceptorIps(
                interceptorCache, cacheKey(domain, queryType), srcIp, domain);
        if (ips != null || queryType != null) {
            return ips;
        }

        List<InetAddress> aRecords = DnsResolveCore.getCachedInterceptorIps(
                interceptorCache, cacheKey(domain, DnsRecordType.A), srcIp, domain);
        List<InetAddress> aaaaRecords = DnsResolveCore.getCachedInterceptorIps(
                interceptorCache, cacheKey(domain, DnsRecordType.AAAA), srcIp, domain);
        if (aRecords == null && aaaaRecords == null) {
            return null;
        }
        if (CollectionUtils.isEmpty(aRecords)) {
            return aaaaRecords == null ? Collections.<InetAddress>emptyList() : aaaaRecords;
        }
        if (CollectionUtils.isEmpty(aaaaRecords)) {
            return aRecords;
        }
        List<InetAddress> merged = new ArrayList<>(aRecords.size() + aaaaRecords.size());
        merged.addAll(aRecords);
        merged.addAll(aaaaRecords);
        return merged;
    }

    void cacheInterceptorResult(String domain, DnsRecordType queryType, List<InetAddress> resolvedIps) {
        if (interceptorCache == null) {
            return;
        }
        if (queryType == null) {
            interceptorCache.put(cacheKey(domain, null), resolvedIps,
                    CachePolicy.absolute(CollectionUtils.isEmpty(resolvedIps) ? negativeTtl : ttl));
        }
        DnsResolveCore.cacheInterceptorResultByFamily(this, domain, resolvedIps);
    }

    private List<InetAddress> weightedHosts(RandomList<InetAddress> ips) {
        InetAddress first = ips.next();
        InetAddress second = ips.next();
        if (Objects.equals(first, second)) {
            return Collections.singletonList(first);
        }
        return Arrays.asList(first, second);
    }

    public boolean addHosts(String host, @NonNull String... ips) {
        return addHosts(host, RandomList.DEFAULT_WEIGHT, Linq.from(ips).select(InetAddress::getByName).toList());
    }

    public boolean addHosts(@NonNull String host, int weight, @NonNull Collection<InetAddress> ips) {
        boolean changed = false;
        host = normalizeHost(host);
        RandomList<InetAddress> list = hosts.computeIfAbsent(host, k -> new RandomList<>());
        for (InetAddress ip : Linq.from(ips).distinct()) {
            // RandomList 自身持有写锁，这里不额外加锁。
            if (list.addOrUpdate(ip, weight)) {
                changed = true;
            }
        }
        return changed;
    }

    public boolean removeHosts(@NonNull String host, Collection<InetAddress> ips) {
        host = normalizeHost(host);
        RandomList<InetAddress> list = hosts.get(host);
        if (list == null) {
            return false;
        }
        return list.removeAll(ips);
    }

    public void addHostsFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!java.nio.file.Files.isRegularFile(path)) {
            log.warn("Hosts file not found, skip {}", filePath);
            return;
        }
        Files.readLines(filePath, line -> {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                return;
            }

            String trimmed = line.trim();
            int spaceIdx = trimmed.indexOf(' ');
            int tabIdx = trimmed.indexOf('\t');

            int splitIdx;
            if (spaceIdx == -1 && tabIdx == -1) {
                log.warn("Invalid line (no delimiter): {}", line);
                return;
            } else if (spaceIdx == -1) {
                splitIdx = tabIdx;
            } else if (tabIdx == -1) {
                splitIdx = spaceIdx;
            } else {
                splitIdx = Math.min(spaceIdx, tabIdx);
            }

            String ip = trimmed.substring(0, splitIdx);
            String host = trimmed.substring(splitIdx + 1).trim();
            addHosts(host, ip);
        });
    }
}
