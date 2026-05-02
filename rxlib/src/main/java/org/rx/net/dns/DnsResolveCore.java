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
import org.rx.core.Tasks;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.transport.ClientDisconnectedException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

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

        RandomList<DnsServer.ResolveInterceptor> interceptors = server.interceptors;
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
                            queryUpstream(upstream, query, isTcp, promise);
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
        queryUpstream(upstream, query, isTcp, promise);
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

    static void evictInterceptorCache(Cache<String, List<InetAddress>> cache, String cacheKey) {
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
        String domain = len > 0 && questionName.charAt(len - 1) == '.' ? questionName.substring(0, len - 1) : questionName;
        return domain.toLowerCase(Locale.ROOT);
    }

    private static DefaultDnsResponse newInterceptorResponse(DefaultDnsQuery query, boolean isTcp, DefaultDnsQuestion question,
                                                            DnsServer server, InetAddress srcIp, String domain,
                                                            List<InetAddress> ips) {
        if (CollectionUtils.isEmpty(ips)) {
            logQuery(srcIp, domain, "EMPTY");
            return DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN);
        }
        DefaultDnsResponse response = DnsMessageUtil.newAddressResponse(query, isTcp, question, server.ttl, ips);
        logQuery(srcIp, domain, response.count(DnsSection.ANSWER), "SHADOW");
        return response;
    }

    private static void resolveByInterceptor(DnsServer server, DnsClient upstream, RandomList<DnsServer.ResolveInterceptor> interceptors,
                                             InetAddress srcIp, String domain, String resolveKey, DefaultDnsQuery query, boolean isTcp,
                                             Promise<List<InetAddress>> resolvePromise, Promise<DefaultDnsResponse> responsePromise) {
        query.retain();
        Tasks.run(() -> {
            List<InetAddress> resolvedIps = null;
            boolean handoffToUpstream = false;
            try {
                resolvedIps = resolveByInterceptorWithFailover(server, interceptors, srcIp, domain);
                if (resolvedIps != null) {
                    CachePolicy policy = CachePolicy.absolute(resolvedIps.isEmpty() ? server.negativeTtl : server.ttl);
                    server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.A), resolvedIps, policy);
                    server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.AAAA), resolvedIps, policy);
                }
                resolvePromise.trySuccess(resolvedIps);
                if (resolvedIps == null) {
                    handoffToUpstream = true;
                    queryUpstream(upstream, query, isTcp, responsePromise);
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

    private static List<InetAddress> resolveByInterceptorWithFailover(DnsServer server, RandomList<DnsServer.ResolveInterceptor> interceptors,
                                                                      InetAddress srcIp, String domain) throws Throwable {
        long now = System.currentTimeMillis();
        DnsServer.ResolveInterceptor selected;
        try {
            selected = interceptors.next();
        } catch (NoSuchElementException e) {
            throw new NoAvailableDnsInterceptorException(domain);
        }
        if (server.isInterceptorAvailable(selected, now)) {
            try {
                List<InetAddress> ips = selected.resolveHost(srcIp, domain);
                server.markInterceptorSuccess(selected);
                return ips;
            } catch (Throwable e) {
                if (!isRecoverableInterceptorFailure(e)) {
                    throw e;
                }
                server.markInterceptorFailure(selected);
                log.warn("dns interceptor temporarily disabled {}+{}: {}", srcIp, domain, e.toString());
                return resolveByAlternateInterceptor(server, interceptors, selected, srcIp, domain, e);
            }
        }
        return resolveByAlternateInterceptor(server, interceptors, selected, srcIp, domain, null);
    }

    private static List<InetAddress> resolveByAlternateInterceptor(DnsServer server, RandomList<DnsServer.ResolveInterceptor> interceptors,
                                                                   DnsServer.ResolveInterceptor skipped, InetAddress srcIp,
                                                                   String domain, Throwable lastFailure) throws Throwable {
        List<DnsServer.ResolveInterceptor> candidates = interceptors.aliveList();
        server.retainInterceptorBreakerKeys(candidates);
        long now = System.currentTimeMillis();
        for (DnsServer.ResolveInterceptor candidate : candidates) {
            if (candidate == skipped || !server.isInterceptorAvailable(candidate, now)) {
                continue;
            }
            try {
                List<InetAddress> ips = candidate.resolveHost(srcIp, domain);
                server.markInterceptorSuccess(candidate);
                return ips;
            } catch (Throwable e) {
                if (!isRecoverableInterceptorFailure(e)) {
                    throw e;
                }
                lastFailure = e;
                server.markInterceptorFailure(candidate);
                log.warn("dns interceptor temporarily disabled {}+{}: {}", srcIp, domain, e.toString());
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new NoAvailableDnsInterceptorException(domain);
    }

    private static boolean isRecoverableInterceptorFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ClientDisconnectedException || cause instanceof TimeoutException || cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void logResolveFailure(InetAddress srcIp, String domain, Throwable e, boolean coalescedWaiter) {
        if (isRecoverableInterceptorFailure(e) || e instanceof NoAvailableDnsInterceptorException) {
            log.warn("dns query {}+{} resolveHost unavailable{}: {}", srcIp, domain,
                    coalescedWaiter ? " (coalesced)" : "", e.toString());
            return;
        }
        log.error("dns query {}+{} resolveHost error", srcIp, domain, e);
    }

    private static void queryUpstream(DnsClient upstream, DefaultDnsQuery query, boolean isTcp, Promise<DefaultDnsResponse> promise) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        upstream.query(question).addListener(f -> {
            try {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                        (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                if (!f.isSuccess()) {
                    log.error("dns query fail {} -> {}", question, envelope != null ? envelope.content() : null, f.cause());
                    promise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                    if (envelope == null) {
                        return;
                    }
                }
                try {
                    DnsResponse response = envelope.content();
                    promise.trySuccess(DnsMessageUtil.newResponse(query, response, isTcp));
                    logQuery(null, normalizeDomain(question.name()), Integer.valueOf(response.count(DnsSection.ANSWER)), "ANSWER");
                } finally {
                    envelope.release();
                }
            } catch (Throwable e) {
                log.error("dns query {} unexpected fail", question, e);
                promise.trySuccess(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
            } finally {
                query.release();
            }
        });
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
