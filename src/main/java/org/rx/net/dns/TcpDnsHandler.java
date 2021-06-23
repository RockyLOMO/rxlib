package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.App;
import org.rx.core.Cache;
import org.rx.core.NQuery;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;
import org.rx.security.AESUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.rx.core.App.cacheKey;

@Slf4j
public class TcpDnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {
    final DnsServer server;
    final DnsClient client;

    public TcpDnsHandler(DnsServer server, EventLoopGroup eventLoopGroup, InetSocketAddress... nameServerList) {
        this.server = server;
        client = new DnsClient(eventLoopGroup, nameServerList);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, DefaultDnsQuery query) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = question.name().substring(0, question.name().length() - 1);
        log.debug("query domain {}", domain);

        byte[] ip = server.getCustomHosts().get(domain);
        if (ip != null) {
            ctx.writeAndFlush(newResponse(query, question, 150, ip));
            return;
        }

        if (domain.endsWith(SocksSupport.FAKE_HOST_SUFFIX)||true) {
            ctx.writeAndFlush(newResponse(query, question, 3600, Sockets.LOOPBACK_ADDRESS.getAddress()));
            return;
        }
        if (server.support != null) {
            App.logMetric("host", domain);
            List<InetAddress> address = Cache.getOrSet(cacheKey("resolveHost:", domain), k -> server.support.resolveHost(AESUtil.encryptToBase64(domain)));
            if (CollectionUtils.isEmpty(address)) {
                ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
                return;
            }
            ctx.writeAndFlush(newResponse(query, question, 600, NQuery.of(address).select(InetAddress::getAddress).toArray()));
            return;
        }

        client.nameResolver.query(question).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("query domain {} fail", domain, f.cause());
                return;
            }
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
            ctx.writeAndFlush(DnsMessageUtil.newResponse(null, null, envelope.content())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        });
    }

    //ttl 秒
    private DefaultDnsResponse newResponse(DefaultDnsQuery query, DefaultDnsQuestion question, long ttl, byte[]... addresses) {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id());
        response.addRecord(DnsSection.QUESTION, question);

        for (byte[] address : addresses) {
            DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(question.name(), DnsRecordType.A, ttl, Unpooled.wrappedBuffer(address));
            response.addRecord(DnsSection.ANSWER, queryAnswer);
        }
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("caught", cause);
    }
}
