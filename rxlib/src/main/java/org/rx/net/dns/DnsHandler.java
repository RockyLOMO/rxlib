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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@ChannelHandler.Sharable
public class DnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {
    public static final DnsHandler DEFAULT = new DnsHandler();
    private static final long MALFORMED_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final AtomicLong MALFORMED_LOG_NANOS = new AtomicLong();
    private static final LongAdder MALFORMED_SUPPRESSED = new LongAdder();

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
            logMalformedPacket(ctx, cause);
            return;
        }

        log.error("dns {} query error", ctx.channel() instanceof io.netty.channel.socket.DatagramChannel ? "UDP" : "TCP", cause);
    }

    private static void logMalformedPacket(ChannelHandlerContext ctx, Throwable cause) {
        long now = System.nanoTime();
        long last = MALFORMED_LOG_NANOS.get();
        if ((last != 0 && now - last < MALFORMED_LOG_INTERVAL_NANOS) || !MALFORMED_LOG_NANOS.compareAndSet(last, now)) {
            MALFORMED_SUPPRESSED.increment();
            return;
        }

        long suppressed = MALFORMED_SUPPRESSED.sumThenReset();
        Object source = ctx.channel().remoteAddress();
        if (source == null) {
            source = Sockets.getAttr(ctx.channel(), DnsServer.ATTR_UDP_SENDER, false);
        }
        if (suppressed > 0) {
            log.warn("丢弃畸形 DNS 数据包 (来源: {}, 已抑制: {}): {}", source, suppressed, cause.getMessage());
        } else {
            log.warn("丢弃畸形 DNS 数据包 (来源: {}): {}", source, cause.getMessage());
        }
    }
}
