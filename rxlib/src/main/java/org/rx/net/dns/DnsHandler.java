package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.dns.*;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

import static org.rx.net.dns.DnsServer.DOMAIN_PREFIX;

@Slf4j
@ChannelHandler.Sharable
public class DnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {
    public static final DnsHandler DEFAULT = new DnsHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultDnsQuery query) {
        Channel ch = ctx.channel();
        DnsServer server = Sockets.getAttr(ch, DnsServer.ATTR_SVR);
        boolean isTcp;
        InetAddress srcIp;
        if (query instanceof DatagramDnsQuery) {
            isTcp = false;
            srcIp = ((DatagramDnsQuery) query).sender().getAddress();
        } else {
            isTcp = true;
            srcIp = ((InetSocketAddress) ch.remoteAddress()).getAddress();
        }
        DnsClient upstream = Sockets.getAttr(ch, DnsServer.ATTR_UPSTREAM);

        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = normalizeDomain(question.name());

        List<InetAddress> hIps = server.getHosts(domain);
        if (!hIps.isEmpty()) {
            ctx.writeAndFlush(newResponse(query, isTcp, question, server.hostsTtl, hIps));
            logQuery(srcIp, domain, hIps.get(0), "HOSTS");
            return;
        }

        if (domain.endsWith(SocksRpcContract.FAKE_HOST_SUFFIX)) {
            ctx.writeAndFlush(newResponse(query, isTcp, question, Short.MAX_VALUE, Collections.singletonList(Sockets.getLoopbackAddress())));
            return;
        }
        RandomList<DnsServer.ResolveInterceptor> interceptors = server.interceptors;
        if (interceptors != null && !domain.endsWith(".lan")) {
            DnsRecordType queryType = question.type();
            if (queryType == DnsRecordType.A || queryType == DnsRecordType.AAAA) {
                String k = server.cacheKey(domain);
                List<InetAddress> ips = server.interceptorCache.get(k);
                if (ips != null) {
                    writeInterceptorResponse(ctx, query, isTcp, question, server, srcIp, domain, ips);
                    return;
                }
                Promise<List<InetAddress>> newPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
                Promise<List<InetAddress>> promise = server.resolvingPromises.putIfAbsent(k, newPromise);
                if (promise == null) {
                    promise = newPromise;
                    server.resolvingKeys.add(k);
                    resolveByInterceptor(server, interceptors, srcIp, domain, k, promise);
                }
                writePromiseResponse(ctx, query, isTcp, question, server, srcIp, domain, promise);
                return;
            }
        }

        query.retain();
        upstream.query(question).addListener(f -> {
            try {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                if (!f.isSuccess()) {
                    log.error("dns query fail {}+{} -> {}", srcIp, domain, envelope != null ? envelope.content() : null, f.cause());
                    // 返回 SERVFAIL (服务器内部错误)
                    ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                    if (envelope == null) {
                        return;
                    }
                }
                try {
                    DnsResponse response = envelope.content();
                    ctx.writeAndFlush(DnsMessageUtil.newResponse(query, response, isTcp));
                    int count = response.count(DnsSection.ANSWER);
                    logQuery(srcIp, domain, Integer.valueOf(count), "ANSWER");
                } finally {
                    envelope.release();
                }
            } finally {
                query.release();
            }
        });
    }

    private void writeInterceptorResponse(ChannelHandlerContext ctx, DefaultDnsQuery query, boolean isTcp,
            DefaultDnsQuestion question, DnsServer server,
            InetAddress srcIp, String domain, List<InetAddress> ips) {
        if (CollectionUtils.isEmpty(ips)) {
            ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
            logQuery(srcIp, domain, "EMPTY");
            return;
        }
        ctx.writeAndFlush(newResponse(query, isTcp, question, server.ttl, ips));
        logQuery(srcIp, domain, ips.get(0), "SHADOW");
    }

    private void writePromiseResponse(ChannelHandlerContext ctx, DefaultDnsQuery query, boolean isTcp, DefaultDnsQuestion question,
                                      DnsServer server, InetAddress srcIp, String domain, Promise<List<InetAddress>> promise) {
        query.retain();
        promise.addListener(f -> ctx.channel().eventLoop().execute(() -> {
            try {
                if (!f.isSuccess()) {
                    log.error("dns query {}+{} resolveHost error", srcIp, domain, f.cause());
                    ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                    return;
                }
                writeInterceptorResponse(ctx, query, isTcp, question, server, srcIp, domain, promise.getNow());
            } finally {
                query.release();
            }
        }));
    }

    private void resolveByInterceptor(DnsServer server, RandomList<DnsServer.ResolveInterceptor> interceptors, InetAddress srcIp,
                                      String domain, String cacheKey, Promise<List<InetAddress>> promise) {
        Tasks.run(() -> {
            try {
                List<InetAddress> resolvedIps;
                try {
                    resolvedIps = interceptors.next().resolveHost(srcIp, domain);
                } catch (Exception e) {
                    log.error("dns query {}+{} resolveHost error", srcIp, domain, e);
                    resolvedIps = null;
                }
                if (resolvedIps == null) {
                    resolvedIps = Collections.emptyList();
                }
                server.interceptorCache.put(cacheKey, resolvedIps,
                        CachePolicy.absolute(resolvedIps.isEmpty() ? server.negativeTtl : server.ttl));
                promise.trySuccess(resolvedIps);
            } catch (Throwable e) {
                promise.tryFailure(e);
            } finally {
                server.resolvingPromises.remove(cacheKey, promise);
                server.resolvingKeys.remove(cacheKey);
            }
        });
    }

    private String normalizeDomain(String questionName) {
        int len = questionName.length();
        return len > 0 && questionName.charAt(len - 1) == '.' ? questionName.substring(0, len - 1) : questionName;
    }

    private void logQuery(InetAddress srcIp, String domain, Object result, String phase) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("dns query {}+{} -> {}[{}]", srcIp, domain, result, phase);
    }

    private void logQuery(InetAddress srcIp, String domain, String result) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("dns query {}+{} -> {}", srcIp, domain, result);
    }

    // ttl seconds
    private DefaultDnsResponse newResponse(DefaultDnsQuery query, boolean isTcp, DefaultDnsQuestion question, long ttl, Iterable<InetAddress> ips) {
        DefaultDnsResponse response = DnsMessageUtil.newResponse(query, isTcp);
        response.addRecord(DnsSection.QUESTION, question);

        for (InetAddress ip : ips) {
            DnsRecordType type = ip instanceof Inet6Address ? DnsRecordType.AAAA : DnsRecordType.A;
            byte[] address = ip.getAddress();
            DefaultDnsRawRecord answer = new DefaultDnsRawRecord(question.name(), type, ttl, Unpooled.wrappedBuffer(address));
            response.addRecord(DnsSection.ANSWER, answer);
        }
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Throwable e = cause;
        boolean isMalformed = false;
        while (e != null) {
            if (e instanceof IndexOutOfBoundsException || e instanceof io.netty.handler.codec.CorruptedFrameException) {
                isMalformed = true;
                break;
            }
            e = e.getCause();
        }

        if (isMalformed) {
            log.warn("丢弃畸形 DNS 数据包 (来源: {}): {}", ctx.channel().remoteAddress(), cause.getMessage());
            return;
        }

        log.error("dns {} query error", ctx.channel() instanceof io.netty.channel.socket.DatagramChannel ? "UDP" : "TCP", cause);
    }
}
