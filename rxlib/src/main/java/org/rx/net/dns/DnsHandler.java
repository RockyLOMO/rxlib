package org.rx.net.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;

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

        query.retain();
        Promise<DefaultDnsResponse> promise = DnsResolveCore.resolve(server, upstream, srcIp, query, isTcp, ctx.executor());
        promise.addListener(f -> {
            try {
                if (!f.isSuccess()) {
                    log.error("dns {} query error", isTcp ? "TCP" : "UDP", f.cause());
                    ctx.writeAndFlush(DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL));
                    return;
                }
                ctx.writeAndFlush(promise.getNow());
            } finally {
                query.release();
            }
        });
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
