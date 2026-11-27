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

/** One association uses the authenticated ingress view and the published upstream snapshot. */
final class RssDnsInterceptor extends DnsQueryUpstream {
    static final class Answers {
        final MemoryCache<String, List<InetAddress>> entries = new MemoryCache<>(b -> b.maximumSize(4096));
    }

    private final DnsServer dns;
    private final UserRuleMatcher matcher;
    private final InetSocketAddress source;
    private final String user;
    private final int ttl;
    private final RandomList<UpstreamSupport> servers;
    private final Answers cache;

    RssDnsInterceptor(InetSocketAddress destination, InetSocketAddress source, TrafficUser user,
                      UserRuleMatcher defaultMatcher, DnsServer dns, int ttl,
                      RandomList<UpstreamSupport> servers, Answers cache) {
        super(destination);
        UserRuleMatcher specific = user instanceof ShadowUser ? ((ShadowUser) user).getRouteMatcher() : null;
        this.matcher = specific == null ? defaultMatcher : specific;
        this.source = source;
        this.user = user == null ? "" : user.getUsername();
        this.dns = dns;
        this.ttl = ttl;
        this.servers = servers;
        this.cache = cache;
    }

    @Override protected byte[] answer(DefaultDnsQuery query, boolean tcp) throws Exception {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String name = question.name().toLowerCase(Locale.ROOT);
        String domain = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        RouteAction action = matcher == null ? RouteAction.PROXY : matcher.match(domain, 53, source);
        if (action == RouteAction.BLOCK) return error(query, DnsResponseCode.REFUSED);
        if (question.dnsClass() != DnsRecord.CLASS_IN) return error(query, DnsResponseCode.NOTIMP);
        boolean address = question.type().equals(DnsRecordType.A) || question.type().equals(DnsRecordType.AAAA);
        if (address) {
            List<InetAddress> hosts = dns.getHosts(domain);
            if (!hosts.isEmpty()) return encode(DnsMessageUtil.newAddressResponse(query, true, question, ttl, hosts));
        }
        SocksRpcContract rpc = null;
        UpstreamSupport peer = null;
        if (action != RouteAction.DIRECT) {
            peer = RssClient.nextHealthyRpcUpstream(servers);
            rpc = peer == null ? null : peer.getFacade();
            if (rpc == null) return error(query, DnsResponseCode.SERVFAIL);
        }
        if (!address) {
            byte[] reply = rpc == null
                    ? DnsRecordExchange.query(DnsRecordExchange.directResolvers(), domain, question.type().intValue(), tcp)
                    : rpc.queryDnsRecord(domain, question.type().intValue(), tcp, SocksRpcContract.rpcToken());
            if (reply == null || reply.length < 12 || reply.length > 65535) return error(query, DnsResponseCode.SERVFAIL);
            reply[0] = (byte) (query.id() >>> 8);
            reply[1] = (byte) query.id();
            return reply;
        }
        // Scope by immutable route action, peer, and client identity. The ACL above is checked on every query.
        String key = action.ordinal() + "/" + user.length() + ":" + user + "/" + (source == null ? "" : source.getAddress())
                + "/" + (peer == null || peer.getRouteId() == null ? "" : peer.getRouteId()) + "/" + domain;
        List<InetAddress> result = cache.entries.get(key);
        if (result == null) {
            if (rpc instanceof RssClient.ForwardingSocksRpcContract) rpc = ((RssClient.ForwardingSocksRpcContract) rpc).delegate;
            result = rpc == null ? dns.resolveDirectAddresses(domain) : rpc.resolveHost(source == null ? null : source.getAddress(), domain);
            if (result == null) return error(query, DnsResponseCode.SERVFAIL);
            cache.entries.put(key, result, CachePolicy.absolute(result.isEmpty() ? DnsServer.DEFAULT_NEGATIVE_TTL : ttl));
        }
        if (result.isEmpty()) return error(query, DnsResponseCode.NXDOMAIN);
        return encode(DnsMessageUtil.newAddressResponse(query, true, question, ttl, result));
    }
}
