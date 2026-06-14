package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.handler.codec.dns.*;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.transport.ClientDisconnectedException;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class DnsResolveCore {
    private DnsResolveCore() {
    }

    static final class NoAvailableDnsInterceptorException extends RuntimeException {
        private static final long serialVersionUID = -7118204188979158189L;

        NoAvailableDnsInterceptorException(String domain) {
            super("No available DNS resolve interceptor for " + domain);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    public static Promise<DefaultDnsResponse> resolve(DnsServer server, DnsClient upstream, InetAddress srcIp,
                                                      DefaultDnsQuery query, boolean isTcp, EventExecutor executor) {
        Promise<DefaultDnsResponse> promise = new DefaultPromise<>(executor);
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = normalizeDomain(question.name());

        List<InetAddress> hIps = server.getHosts(domain);
        if (!hIps.isEmpty()) {
            promise.setSuccess(DnsMessageUtil.newAddressResponse(query, isTcp, question, server.hostsTtl, hIps));
            logQuery(srcIp, domain, hIps.get(0), "HOSTS");
            return promise;
        }

        if (domain.endsWith(SocksRpcContract.FAKE_HOST_SUFFIX)) {
            promise.setSuccess(DnsMessageUtil.newAddressResponse(query, isTcp, question, Short.MAX_VALUE,
                    Collections.singletonList(Sockets.getLoopbackAddress())));
            return promise;
        }

        RandomList<DnsResolveInterceptor> interceptors = server.interceptors;
        DnsRecordType queryType = question.type();
        if (interceptors != null && !domain.endsWith(".lan") && (queryType == DnsRecordType.A || queryType == DnsRecordType.AAAA)) {
            String cacheKey = server.cacheKey(domain, queryType);
            List<InetAddress> ips = getCachedInterceptorIps(server.interceptorCache, cacheKey, srcIp, domain);
            if (ips != null) {
                promise.setSuccess(newInterceptorResponse(query, isTcp, question, server, srcIp, domain, ips));
                return promise;
            }

            String resolveKey = server.resolveKey(domain, queryType);
            Promise<List<InetAddress>> newResolve = new DefaultPromise<>(executor);
            Promise<List<InetAddress>> resolvePromise = server.resolvingPromises.putIfAbsent(resolveKey, newResolve);
            if (resolvePromise == null) {
                resolvePromise = newResolve;
                resolveByInterceptor(server, upstream, interceptors, srcIp, domain, resolveKey, query, isTcp, resolvePromise, promise);
            } else {
                query.retain();
                Promise<List<InetAddress>> waitPromise = resolvePromise;
                waitPromise.addListener(f -> executor.execute(() -> {
                    try {
                        if (!f.isSuccess()) {
                            logResolveFailure(srcIp, domain, f.cause(), true);
                            promise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                            return;
                        }
                        List<InetAddress> result = waitPromise.getNow();
                        if (result == null) {
                            query.retain();
                            queryUpstream(server, upstream, query, isTcp, promise);
                            return;
                        }
                        promise.trySuccess(newInterceptorResponse(query, isTcp, question, server, srcIp, domain, result));
                    } finally {
                        query.release();
                    }
                }));
            }
            return promise;
        }

        query.retain();
        queryUpstream(server, upstream, query, isTcp, promise);
        return promise;
    }

    static List<InetAddress> getCachedInterceptorIps(Cache<String, List<InetAddress>> cache, String cacheKey,
                                                     InetAddress srcIp, String domain) {
        try {
            return cache.get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("dns interceptor cache invalid {}+{} key={}, evict and resolve again: {}",
                    srcIp, domain, cacheKey, e.toString());
            evictInterceptorCache(cache, cacheKey);
            return null;
        }
    }

    @SuppressWarnings("rawtypes")
    static void evictInterceptorCache(Cache cache, String cacheKey) {
        try {
            if (cache instanceof H2StoreCache) {
                ((H2StoreCache<?, ?>) cache).fastRemove(cacheKey);
                return;
            }
            cache.remove(cacheKey);
        } catch (RuntimeException e) {
            log.warn("dns interceptor cache evict failed key={}: {}", cacheKey, e.toString());
        }
    }

    static String normalizeDomain(String questionName) {
        int len = questionName.length();
        if (len == 0) {
            return questionName;
        }

        int end = questionName.charAt(len - 1) == '.' ? len - 1 : len;
        boolean trimDot = end != len;
        boolean hasUpper = false;
        for (int i = 0; i < end; i++) {
            char c = questionName.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasUpper = true;
                break;
            }
        }

        if (!trimDot && !hasUpper) {
            return questionName;
        }
        String normalized = trimDot ? questionName.substring(0, end) : questionName;
        return hasUpper ? asciiLower(normalized) : normalized;
    }

    static String asciiLower(String value) {
        int len = value.length();
        int firstUpper = -1;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                firstUpper = i;
                break;
            }
        }
        if (firstUpper < 0) {
            return value;
        }

        char[] chars = value.toCharArray();
        for (int i = firstUpper; i < len; i++) {
            char c = chars[i];
            if (c >= 'A' && c <= 'Z') {
                chars[i] = (char) (c + 32);
            }
        }
        return new String(chars);
    }

    static void cacheInterceptorResultByFamily(DnsResolverSupport resolver, String domain, List<InetAddress> resolvedIps) {
        List<InetAddress> aRecords = filterByType(resolvedIps, DnsRecordType.A);
        List<InetAddress> aaaaRecords = filterByType(resolvedIps, DnsRecordType.AAAA);
        resolver.interceptorCache.put(resolver.cacheKey(domain, DnsRecordType.A), aRecords,
                CachePolicy.absolute(aRecords.isEmpty() ? resolver.negativeTtl : resolver.ttl));
        resolver.interceptorCache.put(resolver.cacheKey(domain, DnsRecordType.AAAA), aaaaRecords,
                CachePolicy.absolute(aaaaRecords.isEmpty() ? resolver.negativeTtl : resolver.ttl));
    }

    static List<InetAddress> filterByType(List<InetAddress> ips, DnsRecordType queryType) {
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        List<InetAddress> filtered = null;
        for (int i = 0; i < ips.size(); i++) {
            InetAddress ip = ips.get(i);
            boolean ipv6 = ip instanceof Inet6Address;
            boolean match = (queryType == DnsRecordType.AAAA) == ipv6;
            if (!match) {
                if (filtered == null) {
                    filtered = new ArrayList<InetAddress>(ips.size());
                    for (int j = 0; j < i; j++) {
                        filtered.add(ips.get(j));
                    }
                }
                continue;
            }
            if (filtered != null) {
                filtered.add(ip);
            }
        }
        return filtered == null ? ips : filtered.isEmpty() ? Collections.<InetAddress>emptyList() : filtered;
    }

    private static DefaultDnsResponse newInterceptorResponse(DefaultDnsQuery query, boolean isTcp, DefaultDnsQuestion question,
                                                            DnsServer server, InetAddress srcIp, String domain,
                                                            List<InetAddress> ips) {
        List<InetAddress> familyIps = filterByType(ips, question.type());
        if (CollectionUtils.isEmpty(familyIps)) {
            logQuery(srcIp, domain, "EMPTY");
            return DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN);
        }
        DefaultDnsResponse response = DnsMessageUtil.newAddressResponse(query, isTcp, question, server.ttl, familyIps);
        logQuery(srcIp, domain, response.count(DnsSection.ANSWER), "SHADOW");
        return response;
    }

    private static void resolveByInterceptor(DnsServer server, DnsClient upstream, RandomList<DnsResolveInterceptor> interceptors,
                                             InetAddress srcIp, String domain, String resolveKey, DefaultDnsQuery query, boolean isTcp,
                                             Promise<List<InetAddress>> resolvePromise, Promise<DefaultDnsResponse> responsePromise) {
        query.retain();
        Tasks.run(() -> {
            List<InetAddress> resolvedIps = null;
            boolean handoffToUpstream = false;
            try {
                resolvedIps = resolveByInterceptorWithFailover(server, interceptors, srcIp, domain);
                if (resolvedIps != null) {
                    cacheInterceptorResultByFamily(server, domain, resolvedIps);
                }
                resolvePromise.trySuccess(resolvedIps);
                if (resolvedIps == null) {
                    handoffToUpstream = true;
                    queryUpstream(server, upstream, query, isTcp, responsePromise);
                    return;
                }
                DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
                responsePromise.trySuccess(newInterceptorResponse(query, isTcp, question, server, srcIp, domain, resolvedIps));
            } catch (Throwable e) {
                logResolveFailure(srcIp, domain, e, false);
                resolvePromise.tryFailure(e);
                responsePromise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
            } finally {
                if (!handoffToUpstream) {
                    query.release();
                }
                server.resolvingPromises.remove(resolveKey, resolvePromise);
            }
        });
    }

    static List<InetAddress> resolveByInterceptorWithFailover(DnsResolverSupport resolver, RandomList<DnsResolveInterceptor> interceptors,
                                                              InetAddress srcIp, String domain) throws Throwable {
        long now = System.currentTimeMillis();
        DnsResolveInterceptor selected;
        try {
            selected = interceptors.next();
        } catch (NoSuchElementException e) {
            throw new NoAvailableDnsInterceptorException(domain);
        }
        if (resolver.isInterceptorAvailable(selected, now)) {
            try {
                List<InetAddress> ips = selected.resolveHost(srcIp, domain);
                resolver.markInterceptorSuccess(selected);
                return ips;
            } catch (Throwable e) {
                if (!isRecoverableInterceptorFailure(e)) {
                    throw e;
                }
                resolver.markInterceptorFailure(selected);
                log.warn("dns interceptor temporarily disabled {}+{}: {}", srcIp, domain, e.toString());
                return resolveByAlternateInterceptor(resolver, interceptors, selected, srcIp, domain, e);
            }
        }
        return resolveByAlternateInterceptor(resolver, interceptors, selected, srcIp, domain, null);
    }

    private static List<InetAddress> resolveByAlternateInterceptor(DnsResolverSupport resolver, RandomList<DnsResolveInterceptor> interceptors,
                                                                   DnsResolveInterceptor skipped, InetAddress srcIp,
                                                                   String domain, Throwable lastFailure) throws Throwable {
        List<DnsResolveInterceptor> candidates = interceptors.aliveList();
        resolver.retainInterceptorBreakerKeys(candidates);
        long now = System.currentTimeMillis();
        for (DnsResolveInterceptor candidate : candidates) {
            if (candidate == skipped || !resolver.isInterceptorAvailable(candidate, now)) {
                continue;
            }
            try {
                List<InetAddress> ips = candidate.resolveHost(srcIp, domain);
                resolver.markInterceptorSuccess(candidate);
                return ips;
            } catch (Throwable e) {
                if (!isRecoverableInterceptorFailure(e)) {
                    throw e;
                }
                lastFailure = e;
                resolver.markInterceptorFailure(candidate);
                log.warn("dns interceptor temporarily disabled {}+{}: {}", srcIp, domain, e.toString());
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new NoAvailableDnsInterceptorException(domain);
    }

    static boolean isRecoverableInterceptorFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ClientDisconnectedException || cause instanceof TimeoutException || cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    static void logResolveFailure(InetAddress srcIp, String domain, Throwable e, boolean coalescedWaiter) {
        if (isRecoverableInterceptorFailure(e) || e instanceof NoAvailableDnsInterceptorException) {
            log.warn("dns query {}+{} resolveHost unavailable{}: {}", srcIp, domain,
                    coalescedWaiter ? " (coalesced)" : "", e.toString());
            return;
        }
        log.error("dns query {}+{} resolveHost error", srcIp, domain, e);
    }

    private static void queryUpstream(DnsServer server, DnsClient upstream, DefaultDnsQuery query, boolean isTcp,
                                      Promise<DefaultDnsResponse> promise) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = normalizeDomain(question.name());
        String cacheKey = server.responseCacheKey(domain, question.type(), question.dnsClass());
        RxConfig.DnsCacheConfig config = RxConfig.INSTANCE.getNet().getDns().getCache();
        config.normalize();

        DnsResponseCacheEntry cached = getCachedResponse(server, cacheKey, domain);
        long now = System.currentTimeMillis();
        if (cached != null && cached.isFresh(now)) {
            server.responseCacheFreshHits.incrementAndGet();
            promise.trySuccess(cached.newResponse(query, isTcp, false, config.getServeExpiredReplyTtlSeconds(), now));
            if (cached.shouldPrefetch(config, now)) {
                refreshUpstream(server, upstream, copyQuestion(question), cacheKey, domain, "prefetch");
            }
            query.release();
            logQuery(null, domain, Integer.valueOf(cached.answers.size()), "CACHE");
            return;
        }

        boolean staleUsable = cached != null && cached.isServeExpiredAllowed(config, now);
        if (staleUsable && config.getServeExpiredClientTimeoutMillis() == 0) {
            server.responseCacheStaleHits.incrementAndGet();
            promise.trySuccess(cached.newResponse(query, isTcp, true, config.getServeExpiredReplyTtlSeconds(), now));
            refreshUpstream(server, upstream, copyQuestion(question), cacheKey, domain, "stale-refresh");
            query.release();
            logQuery(null, domain, Integer.valueOf(cached.answers.size()), "STALE");
            return;
        }

        server.responseCacheMisses.incrementAndGet();
        queryUpstream0(server, upstream, query, isTcp, promise, copyQuestion(question), cacheKey, domain,
                staleUsable ? cached : null, config);
    }

    static DnsResponseCacheEntry getCachedResponse(DnsServer server, String cacheKey, String domain) {
        Cache<String, DnsResponseCacheEntry> cache = server.responseCache;
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("dns response cache invalid {} key={}, evict and resolve again: {}", domain, cacheKey, e.toString());
            evictInterceptorCache(cache, cacheKey);
            return null;
        }
    }

    private static void queryUpstream0(DnsServer server, DnsClient upstream, DefaultDnsQuery query, boolean isTcp,
                                       Promise<DefaultDnsResponse> promise, DefaultDnsQuestion question,
                                       String cacheKey, String domain, DnsResponseCacheEntry stale,
                                       RxConfig.DnsCacheConfig config) {
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledFuture<?> timeoutFuture = null;
        if (stale != null && config.getServeExpiredClientTimeoutMillis() > 0) {
            timeoutFuture = upstream.executor.schedule(() -> {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }

                long now = System.currentTimeMillis();
                server.responseCacheStaleHits.incrementAndGet();
                server.responseCacheUpstreamFailServedExpired.incrementAndGet();
                promise.trySuccess(stale.newResponse(query, isTcp, true, config.getServeExpiredReplyTtlSeconds(), now));
                logQuery(null, domain, Integer.valueOf(stale.answers.size()), "STALE_TIMEOUT");
            }, config.getServeExpiredClientTimeoutMillis(), TimeUnit.MILLISECONDS);
        }

        final ScheduledFuture<?> finalTimeoutFuture = timeoutFuture;
        upstream.query(question).addListener(f -> {
            try {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                        (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                if (!f.isSuccess()) {
                    log.error("dns query fail {} -> {}", question, envelope != null ? envelope.content() : null, f.cause());
                    if (stale != null && completed.compareAndSet(false, true)) {
                        server.responseCacheStaleHits.incrementAndGet();
                        server.responseCacheUpstreamFailServedExpired.incrementAndGet();
                        promise.trySuccess(stale.newResponse(query, isTcp, true,
                                config.getServeExpiredReplyTtlSeconds(), System.currentTimeMillis()));
                        logQuery(null, domain, Integer.valueOf(stale.answers.size()), "STALE_FAIL");
                    } else if (completed.compareAndSet(false, true)) {
                        promise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                    }
                    if (envelope != null) {
                        envelope.release();
                    }
                    return;
                }
                try {
                    DnsResponse response = envelope.content();
                    DnsResponseCacheEntry entry = cacheUpstreamResponse(server, cacheKey, response, config);
                    if (completed.compareAndSet(false, true)) {
                        promise.trySuccess(DnsMessageUtil.newResponse(query, response, isTcp));
                        logQuery(null, domain, Integer.valueOf(response.count(DnsSection.ANSWER)), "ANSWER");
                    } else if (entry != null) {
                        logQuery(null, domain, Integer.valueOf(entry.answers.size()), "REFRESH");
                    }
                } finally {
                    envelope.release();
                }
            } catch (Throwable e) {
                log.error("dns query {} unexpected fail", question, e);
                if (stale != null && completed.compareAndSet(false, true)) {
                    server.responseCacheStaleHits.incrementAndGet();
                    server.responseCacheUpstreamFailServedExpired.incrementAndGet();
                    promise.trySuccess(stale.newResponse(query, isTcp, true,
                            config.getServeExpiredReplyTtlSeconds(), System.currentTimeMillis()));
                    logQuery(null, domain, Integer.valueOf(stale.answers.size()), "STALE_ERROR");
                } else if (completed.compareAndSet(false, true)) {
                    promise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                }
            } finally {
                if (finalTimeoutFuture != null) {
                    finalTimeoutFuture.cancel(false);
                }
                query.release();
            }
        });
    }

    private static DefaultDnsQuestion copyQuestion(DefaultDnsQuestion question) {
        return new DefaultDnsQuestion(question.name(), question.type(), question.dnsClass());
    }

    private static void refreshUpstream(DnsServer server, DnsClient upstream, DefaultDnsQuestion question,
                                        String cacheKey, String domain, String reason) {
        Promise<DnsResponseCacheEntry> refreshPromise = new DefaultPromise<DnsResponseCacheEntry>(upstream.executor);
        Promise<DnsResponseCacheEntry> old = server.responseRefreshPromises.putIfAbsent(cacheKey, refreshPromise);
        if (old != null) {
            return;
        }

        server.responseCachePrefetchStarted.incrementAndGet();
        upstream.query(question).addListener(f -> {
            try {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                        (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                if (!f.isSuccess()) {
                    server.responseCachePrefetchFailure.incrementAndGet();
                    refreshPromise.tryFailure(f.cause());
                    log.warn("dns response cache {} refresh fail {}: {}", reason, domain, f.cause().toString());
                    if (envelope != null) {
                        envelope.release();
                    }
                    return;
                }
                try {
                    DnsResponseCacheEntry entry = cacheUpstreamResponse(server, cacheKey, envelope.content(),
                            RxConfig.INSTANCE.getNet().getDns().getCache());
                    if (entry != null) {
                        server.responseCachePrefetchSuccess.incrementAndGet();
                        refreshPromise.trySuccess(entry);
                    } else {
                        server.responseCachePrefetchFailure.incrementAndGet();
                        refreshPromise.trySuccess(null);
                    }
                } finally {
                    envelope.release();
                }
            } catch (Throwable e) {
                server.responseCachePrefetchFailure.incrementAndGet();
                refreshPromise.tryFailure(e);
                log.warn("dns response cache {} refresh error {}: {}", reason, domain, e.toString());
            } finally {
                server.responseRefreshPromises.remove(cacheKey, refreshPromise);
            }
        });
    }

    static DnsResponseCacheEntry cacheUpstreamResponse(DnsServer server, String cacheKey, DnsResponse response,
                                                       RxConfig.DnsCacheConfig config) {
        if (server.responseCache == null) {
            return null;
        }
        config.normalize();
        DnsResponseCacheEntry entry = DnsResponseCacheEntry.tryCreate(response, server.negativeTtl);
        if (entry == null) {
            return null;
        }

        CachePolicy policy;
        if (config.isServeExpired()) {
            int staleSeconds = config.getServeExpiredTtlSeconds();
            if (staleSeconds == 0) {
                policy = new CachePolicy(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650), 0);
            } else {
                long expireAt = entry.freshExpireMillis() + ((long) staleSeconds * 1000L);
                policy = new CachePolicy(expireAt < 0 ? Long.MAX_VALUE : expireAt, 0);
            }
        } else {
            policy = CachePolicy.absolute(entry.freshTtlSeconds);
        }
        server.responseCache.put(cacheKey, entry, policy);
        return entry;
    }

    private static void logQuery(InetAddress srcIp, String domain, Object result, String phase) {
        if (log.isDebugEnabled()) {
            log.debug("dns query {}+{} -> {}[{}]", srcIp, domain, result, phase);
        }
    }

    private static void logQuery(InetAddress srcIp, String domain, String result) {
        if (log.isDebugEnabled()) {
            log.debug("dns query {}+{} -> {}", srcIp, domain, result);
        }
    }
}
