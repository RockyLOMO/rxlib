package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.CachePolicy;
import org.rx.core.Linq;
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
        boolean isTcp = !(ch instanceof DatagramChannel);
        DnsClient upstream = Sockets.getAttr(ch, DnsServer.ATTR_UPSTREAM);

        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
//        log.debug("dns query name={}", question.name());
        String domain = question.name().substring(0, question.name().length() - 1);

        List<InetAddress> hIps = server.getHosts(domain);
        if (!hIps.isEmpty()) {
            ctx.writeAndFlush(newResponse(query, isTcp, question, server.hostsTtl, hIps));
            log.info("dns query {} -> {}[HOSTS]", domain, hIps.get(0).getHostAddress());
            return;
        }

        if (domain.endsWith(SocksRpcContract.FAKE_HOST_SUFFIX)) {
            ctx.writeAndFlush(newResponse(query, isTcp, question, Short.MAX_VALUE, Collections.singletonList(Sockets.getLoopbackAddress())));
            return;
        }
        RandomList<DnsServer.ResolveInterceptor> interceptors = server.interceptors;
        if (interceptors != null) {
            String k = DOMAIN_PREFIX + domain;
            List<InetAddress> ips = server.interceptorCache.get(k);
            if (ips == null) {
                //cache value can't be null
                server.interceptorCache.put(k, ips = interceptors.next().resolveHost(domain),
                        CachePolicy.absolute(CollectionUtils.isEmpty(ips) ? 5 : server.ttl));
            }
            if (CollectionUtils.isEmpty(ips)) {
                ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
                log.info("dns query {} -> EMPTY", domain);
                return;
            }
            ctx.writeAndFlush(newResponse(query, isTcp, question, server.ttl, ips));
            log.info("dns query {} -> {}[SHADOW]", domain, ips.get(0).getHostAddress());
            return;
        }

        query.retain();
        upstream.query(question).addListener(f -> {
            try {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                if (!f.isSuccess()) {
                    log.error("dns query fail {} -> {}", domain, envelope != null ? envelope.content() : null, f.cause());
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
                    log.info("dns query {} -> {}[ANSWER]", domain, count);
                } finally {
                    envelope.release();
                }
            } finally {
                query.release();
            }
        });
    }

    //ttl seconds
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//        if (cause instanceof io.netty.handler.codec.DecoderException
//                || cause instanceof IndexOutOfBoundsException) {
//            log.warn("dns decode error: {}", cause.getMessage());
//        } else {
        log.error("dns query error", cause);
//        }
    }
}
