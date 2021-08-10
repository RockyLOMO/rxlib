package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;
import org.rx.core.NQuery;
import org.rx.core.cache.HybridCache;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;
import static org.rx.core.Tasks.awaitQuietly;

@Slf4j
public class DnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {
    static final String DOMAIN_PREFIX = "resolveHost:";
    final DnsServer server;
    final boolean isTcp;
    final DnsClient client;
    final Cache<Object, Object> cache;

    public DnsHandler(DnsServer server, boolean isTcp, EventLoopGroup eventLoopGroup, InetSocketAddress... nameServerList) {
        this.server = server;
        this.isTcp = isTcp;
        client = new DnsClient(eventLoopGroup, nameServerList);
        if (server.support == null) {
            cache = null;
        } else {
            cache = Cache.getInstance(Cache.DISTRIBUTED_CACHE);
            ((HybridCache<Object, Object>) cache).onExpired = (s, e) -> {
                Map.Entry<Object, Object> entry = e.getValue();
                String key;
                if ((key = as(entry.getKey(), String.class)) == null || !key.startsWith(DOMAIN_PREFIX)) {
                    entry.setValue(null);
                    return;
                }

                String domain = key.substring(DOMAIN_PREFIX.length());
                List<InetAddress> lastAddresses = (List<InetAddress>) entry.getValue();
                List<InetAddress> addresses = awaitQuietly(() -> {
                    List<InetAddress> list = server.support.next().getSupport().resolveHost(domain);
                    if (CollectionUtils.isEmpty(list)) {
                        return null;
                    }
                    cache.put(key, list, CacheExpirations.absolute(server.ttl));
                    log.info("renewAsync {} lastAddresses={} addresses={}", key, lastAddresses, list);
                    return list;
                }, SocksSupport.ASYNC_TIMEOUT);
                if (!CollectionUtils.isEmpty(addresses)) {
                    entry.setValue(addresses);
                }
                log.info("renew {} lastAddresses={} currentAddresses={}", key, lastAddresses, entry.getValue());
            };
        }
    }

    private Cache<String, List<InetAddress>> cache() {
        return (Cache) cache;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, DefaultDnsQuery query) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = question.name().substring(0, question.name().length() - 1);
        log.debug("query domain {}", domain);

        byte[] ip = server.getHosts().get(domain);
        if (ip != null) {
            ctx.writeAndFlush(newResponse(query, question, 150, ip));
            return;
        }

        if (!domain.contains(".")) {
            log.warn("Invalid domain {}", domain);
            ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
            return;
        }
        if (domain.endsWith(SocksSupport.FAKE_HOST_SUFFIX)) {
            ctx.writeAndFlush(newResponse(query, question, Short.MAX_VALUE, Sockets.LOOPBACK_ADDRESS.getAddress()));
            return;
        }
        if (server.support != null) {
            //未命中也缓存
            $<Boolean> isEmpty = $(false);
            List<InetAddress> addresses = cache().get(DOMAIN_PREFIX + domain,
                    k -> {
                        List<InetAddress> tmp = isNull(server.support.next().getSupport().resolveHost(domain), Collections.emptyList());
                        isEmpty.v = tmp.isEmpty();
                        return tmp;
                    },
                    CacheExpirations.absolute(isEmpty.v ? 60 : server.ttl));
            if (CollectionUtils.isEmpty(addresses)) {
                ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN));
                return;
            }
            ctx.writeAndFlush(newResponse(query, question, server.ttl, NQuery.of(addresses).select(InetAddress::getAddress).toArray()));
            return;
        }

        client.nameResolver.query(question).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("query domain {} fail", domain, f.cause());
                return;
            }
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
            ctx.writeAndFlush(DnsMessageUtil.newResponse(query, envelope.content(), isTcp)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        });
    }

    //ttl 秒
    private DefaultDnsResponse newResponse(DefaultDnsQuery query, DefaultDnsQuestion question, long ttl, byte[]... addresses) {
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
        log.error("caught", cause);
    }
}
