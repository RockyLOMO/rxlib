package org.rx.util.rss;

import io.netty.handler.codec.dns.*;
import org.rx.bean.RandomList;
import org.rx.core.CachePolicy;
import org.rx.core.cache.MemoryCache;
import org.rx.net.dns.*;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.TrafficUser;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

/** Resolves the active runtime generation per query so hot reloads take effect immediately without pinning old snapshots. */
final class RssDnsInterceptor extends DnsQueryUpstream {
    static final class Answers {
        final MemoryCache<String, List<InetAddress>> entries = new MemoryCache<>(b -> b.maximumSize(4096));
    }

    private final RssRuntime runtime;
    private final InetSocketAddress source;
    private final String user;
    private final boolean shadowAuthenticated;

    RssDnsInterceptor(InetSocketAddress destination, InetSocketAddress source, TrafficUser user, RssRuntime runtime) {
        super(destination);
        this.runtime = runtime;
        this.source = source;
        this.shadowAuthenticated = user instanceof ShadowUser;
        this.user = user == null ? "" : user.getUsername();
    }

    @Override protected byte[] answer(DefaultDnsQuery query, boolean tcp) throws Exception {
        RssRuntime.RuntimeGeneration gen = runtime == null ? null : runtime.currentGeneration();
        if (gen == null || gen.conf == null || gen.upstreamSnapshot == null) {
            return error(query, DnsResponseCode.SERVFAIL);
        }
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String name = question.name().toLowerCase(Locale.ROOT);
        String domain = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;

        TrafficUser effectiveUser = null;
        UserRuleMatcher matcher;
        if (shadowAuthenticated) {
            ShadowUser current = runtime.authenticator.getShadowStore().get(user);
            if (current == null) {
                return error(query, DnsResponseCode.REFUSED);
            }
            effectiveUser = current;
            matcher = current.getRouteMatcher() != null ? current.getRouteMatcher() : gen.conf.defaultRouteMatcher;
        } else {
            matcher = gen.conf.defaultRouteMatcher;
        }

        RouteAction action = matcher == null ? RouteAction.PROXY : matcher.match(domain, 53, source);
        if (action == RouteAction.BLOCK) return error(query, DnsResponseCode.REFUSED);
        if (question.dnsClass() != DnsRecord.CLASS_IN) return error(query, DnsResponseCode.NOTIMP);

        RssRuntime.UpstreamSnapshot snapshot = gen.upstreamSnapshot;
        if (!snapshot.tryRetainDnsQuery()) {
            return error(query, DnsResponseCode.SERVFAIL);
        }
        try {
            DnsServer dns = gen.dnsServer;
            int ttl = 60 * gen.conf.dnsTtlMinutes;
            boolean address = question.type().equals(DnsRecordType.A) || question.type().equals(DnsRecordType.AAAA);
            if (address && dns != null) {
                List<InetAddress> hosts = dns.getHosts(domain);
                if (!hosts.isEmpty()) return encode(DnsMessageUtil.newAddressResponse(query, true, question, ttl, hosts));
            }
            SocksRpcContract rpc = null;
            UpstreamSupport peer = null;
            if (action != RouteAction.DIRECT) {
                RandomList<UpstreamSupport> servers = RssClient.resolveUserSocksServers(
                        snapshot.socksServers, snapshot.userSocksServers, effectiveUser);
                peer = RssClient.nextHealthyRpcUpstream(servers);
                rpc = peer == null ? null : peer.getFacade();
                if (rpc == null) return error(query, DnsResponseCode.SERVFAIL);
            }
            if (!address) {
                byte[] reply;
                if (rpc == null) {
                    reply = DnsRecordExchange.query(DnsRecordExchange.directResolvers(), domain, question.type().intValue(), tcp);
                } else {
                    try {
                        reply = rpc.queryDnsRecord(domain, question.type().intValue(), tcp, SocksRpcContract.rpcToken());
                    } catch (Exception e) {
                        peer.recordFailure(UpstreamSupport.Capability.RPC);
                        throw e;
                    }
                    if (reply == null || reply.length < 12 || reply.length > 65535) {
                        peer.recordFailure(UpstreamSupport.Capability.RPC);
                        return error(query, DnsResponseCode.SERVFAIL);
                    }
                    peer.recordSuccess(UpstreamSupport.Capability.RPC);
                }
                if (reply == null || reply.length < 12 || reply.length > 65535) return error(query, DnsResponseCode.SERVFAIL);
                reply[0] = (byte) (query.id() >>> 8);
                reply[1] = (byte) query.id();
                return reply;
            }
            // Scope by immutable route action, peer, and client identity. The ACL above is checked on every query.
            String key = action.ordinal() + "/" + user.length() + ":" + user + "/" + (source == null ? "" : source.getAddress())
                    + "/" + (peer == null || peer.getRouteId() == null ? "" : peer.getRouteId()) + "/" + domain;
            Answers cache = snapshot.dnsAnswers;
            List<InetAddress> result = cache.entries.get(key);
            if (result == null) {
                if (rpc == null) {
                    if (dns == null) return error(query, DnsResponseCode.SERVFAIL);
                    result = dns.resolveDirectAddresses(domain);
                } else {
                    SocksRpcContract delegate = rpc instanceof RssClient.ForwardingSocksRpcContract
                            ? ((RssClient.ForwardingSocksRpcContract) rpc).delegate : rpc;
                    try {
                        result = delegate.resolveHost(source == null ? null : source.getAddress(), domain);
                    } catch (Exception e) {
                        peer.recordFailure(UpstreamSupport.Capability.RPC);
                        throw e;
                    }
                    if (result == null) {
                        peer.recordFailure(UpstreamSupport.Capability.RPC);
                        return error(query, DnsResponseCode.SERVFAIL);
                    }
                    peer.recordSuccess(UpstreamSupport.Capability.RPC);
                }
                if (result == null) return error(query, DnsResponseCode.SERVFAIL);
                cache.entries.put(key, result, CachePolicy.absolute(result.isEmpty() ? DnsServer.DEFAULT_NEGATIVE_TTL : ttl));
            }
            if (result.isEmpty()) return error(query, DnsResponseCode.NXDOMAIN);
            return encode(DnsMessageUtil.newAddressResponse(query, true, question, ttl, result));
        } finally {
            snapshot.releaseDnsQuery();
        }
    }
}
