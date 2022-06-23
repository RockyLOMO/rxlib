package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.bean.RandomList;
import org.rx.core.CachePolicy;
import org.rx.core.NQuery;
import org.rx.exception.ExceptionHandler;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.*;
import static org.rx.net.dns.DnsServer.DOMAIN_PREFIX;

@Slf4j
@ChannelHandler.Sharable
public class DnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {
    final DnsServer server;
    final boolean isTcp;
    final DnsClient client;

    public DnsHandler(DnsServer server, boolean isTcp, List<InetSocketAddress> nameServerList) {
        this.server = server;
        this.isTcp = isTcp;
        client = new DnsClient(nameServerList);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, DefaultDnsQuery query) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = question.name().substring(0, question.name().length() - 1);

        List<InetAddress> hIps = server.getHosts(domain);
        if (!hIps.isEmpty()) {
            ctx.writeAndFlush(newResponse(query, question, server.hostsTtl, NQuery.of(hIps).select(InetAddress::getAddress)));
            log.debug("query domain by hosts {} -> {}", domain, hIps.size());
            return;
        }

        if (domain.endsWith(SocksSupport.FAKE_HOST_SUFFIX)) {
            ctx.writeAndFlush(newResponse(query, question, Short.MAX_VALUE, Collections.singletonList(Sockets.loopbackAddress().getAddress())));
            return;
        }
        RandomList<UpstreamSupport> shadowServers = server.shadowServers;
        if (shadowServers != null) {
            //未命中也缓存
            $<Boolean> isEmpty = $(false);
            List<InetAddress> sIps = server.shadowCache.get(DOMAIN_PREFIX + domain,
                    k -> {
                        List<InetAddress> tmp = ifNull(sneakyInvoke(() -> shadowServers.next().getSupport().resolveHost(domain), 2), Collections.emptyList());
                        isEmpty.v = tmp.isEmpty();
                        return tmp;
                    },
                    CachePolicy.absolute(isEmpty.v ? 20 : server.ttl));
            if (CollectionUtils.isEmpty(sIps)) {
                ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
                return;
            }
            ctx.writeAndFlush(newResponse(query, question, server.ttl, NQuery.of(sIps).select(InetAddress::getAddress)));
            log.info("query domain by shadow {} -> {}", domain, sIps.size());
            return;
        }

        client.query(question).addListener(f -> {
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
            if (!f.isSuccess()) {
                ExceptionHandler.INSTANCE.log("query domain fail {} -> {}", domain, envelope, f.cause());
//                ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
//                ctx.writeAndFlush(newResponse(query, question, server.ttl));
                if (envelope == null) {
                    return;
                }
            }
            try {
                ctx.writeAndFlush(DnsMessageUtil.newResponse(query, envelope.content(), isTcp));
//                log.debug("query domain {} -> {}", domain, envelope.content());
            } finally {
                envelope.release();
            }
        });
    }

    //ttl 秒
    private DefaultDnsResponse newResponse(DefaultDnsQuery query, DefaultDnsQuestion question, long ttl, Iterable<byte[]> addresses) {
        DefaultDnsResponse response = DnsMessageUtil.newResponse(query, isTcp);
        response.addRecord(DnsSection.QUESTION, question);

        for (byte[] address : addresses) {
            DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(question.name(), DnsRecordType.A, ttl, Unpooled.wrappedBuffer(address));
            response.addRecord(DnsSection.ANSWER, queryAnswer);
        }
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ExceptionHandler.INSTANCE.log(cause);
    }
}
