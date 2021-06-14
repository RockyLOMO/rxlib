package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Slf4j
public class DnsHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
    final DnsServer server;
    final DnsClient client;
    @Getter(lazy = true)
    private final DnsClient outland = DnsClient.outlandClient();

    public DnsHandler(DnsServer server, EventLoopGroup eventLoopGroup, InetSocketAddress... nameServerList) {
        this.server = server;
        client = new DnsClient(eventLoopGroup, nameServerList);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        String domain = question.name().substring(0, question.name().length() - 1);
        log.debug("query domain {}", domain);

        DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
        byte[] ip = server.getCustomHosts().get(domain);
        if (ip != null) {
            response.addRecord(DnsSection.QUESTION, question);

            DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(question.name(), DnsRecordType.A, 300, Unpooled.wrappedBuffer(ip));
            response.addRecord(DnsSection.ANSWER, queryAnswer);
            ctx.writeAndFlush(response);
            return;
        }

        if (server.support != null) {
            response.addRecord(DnsSection.QUESTION, question);

            InetAddress address = server.support.resolveHost(domain).get(0);
            //ttl 秒
            DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(question.name(), DnsRecordType.A, 600, Unpooled.wrappedBuffer(address.getAddress()));
            response.addRecord(DnsSection.ANSWER, queryAnswer);
            ctx.writeAndFlush(response);
            return;
        }

        client.nameResolver.query(question).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("query domain {} fail", domain, f.cause());
                return;
            }
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
            copySections(envelope.content(), response);
            ctx.writeAndFlush(response);
        });
    }

    private void copySections(DnsResponse r1, DnsResponse r2) {
        for (DnsSection section : DnsSection.values()) {
            for (int i = 0; i < r1.count(section); i++) {
                DnsRecord record = r1.recordAt(section, i);
                r2.addRecord(section, record);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("caught", cause);
    }
}
