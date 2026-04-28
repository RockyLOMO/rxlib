package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.handler.codec.dns.*;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

@Slf4j
public final class DnsResolveCore {
    private DnsResolveCore() {
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
            List<InetAddress> ips = server.interceptorCache.get(cacheKey);
            if (ips != null) {
                promise.setSuccess(newInterceptorResponse(query, isTcp, question, server, srcIp, domain, ips));
                return promise;
            }

            String resolveKey = server.resolveKey(domain);
            Promise<List<InetAddress>> newResolve = new DefaultPromise<>(executor);
            Promise<List<InetAddress>> resolvePromise = server.resolvingPromises.putIfAbsent(resolveKey, newResolve);
            if (resolvePromise == null) {
                resolvePromise = newResolve;
                server.resolvingKeys.add(resolveKey);
                resolveByInterceptor(server, upstream, interceptors, srcIp, domain, resolveKey, query, isTcp, resolvePromise, promise);
            } else {
                query.retain();
                Promise<List<InetAddress>> waitPromise = resolvePromise;
                waitPromise.addListener(f -> executor.execute(() -> {
                    try {
                        if (!f.isSuccess()) {
                            promise.tryFailure(f.cause());
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

    static String normalizeDomain(String questionName) {
        int len = questionName.length();
        return len > 0 && questionName.charAt(len - 1) == '.' ? questionName.substring(0, len - 1) : questionName;
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
            try {
                resolvedIps = interceptors.next().resolveHost(srcIp, domain);
                if (resolvedIps != null) {
                    CachePolicy policy = CachePolicy.absolute(resolvedIps.isEmpty() ? server.negativeTtl : server.ttl);
                    server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.A), resolvedIps, policy);
                    server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.AAAA), resolvedIps, policy);
                }
                resolvePromise.trySuccess(resolvedIps);
                if (resolvedIps == null) {
                    queryUpstream(upstream, query, isTcp, responsePromise);
                    return;
                }
                DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
                responsePromise.trySuccess(newInterceptorResponse(query, isTcp, question, server, srcIp, domain, resolvedIps));
            } catch (Throwable e) {
                log.error("dns query {}+{} resolveHost error", srcIp, domain, e);
                resolvePromise.tryFailure(e);
                responsePromise.tryFailure(e);
            } finally {
                if (responsePromise.isDone()) {
                    query.release();
                }
                server.resolvingPromises.remove(resolveKey, resolvePromise);
                server.resolvingKeys.remove(resolveKey);
            }
        });
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
                promise.tryFailure(e);
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
