package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.net.BandwidthLimiter;
import org.rx.net.Sockets;
import org.rx.net.TcpBandwidthPacer;

@Slf4j
@ChannelHandler.Sharable
public class SocksTcpBackendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final SocksTcpBackendRelayHandler DEFAULT = new SocksTcpBackendRelayHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        SocksUserTraffic.recordRead(sc, msg);
        touchHalfCloseWatch(sc.inbound);

        if (msg instanceof ByteBuf) {
            TcpBandwidthPacer dl = sc.inbound.attr(SocksTcpFrontendRelayHandler.ATTR_DOWNLOAD_PACER).get();
            BandwidthLimiter limiter = SocksTcpFrontendRelayHandler.resolveBandwidthLimiter(sc.inbound);
            if (dl != null || (limiter != null && limiter.isEnabled(BandwidthLimiter.Direction.DOWNLOAD))) {
                if (dl == null) {
                    Channel inbound = sc.inbound;
                    dl = new TcpBandwidthPacer(
                            outbound,
                            inbound,
                            BandwidthLimiter.Direction.DOWNLOAD,
                            () -> SocksTcpFrontendRelayHandler.resolveBandwidthLimiter(inbound),
                            buf -> {
                                touchHalfCloseWatch(inbound);
                                inbound.writeAndFlush(buf);
                            });
                    inbound.attr(SocksTcpFrontendRelayHandler.ATTR_DOWNLOAD_PACER).set(dl);
                }
                dl.offer((ByteBuf) msg);
                return;
            }
        }

        log.debug("TCP RELAY {}[{}] => {}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress());
        sc.inbound.writeAndFlush(msg);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        SocksContext sc = SocksContext.ctx(ctx.channel(), false);
        if (sc != null && sc.inbound != null) {
            TcpBandwidthPacer up = sc.inbound.attr(SocksTcpFrontendRelayHandler.ATTR_UPLOAD_PACER).get();
            if (up != null) {
                up.onWritabilityChanged();
            }
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocksContext sc = SocksContext.ctx(ctx.channel());
        super.channelInactive(ctx);
        Channel inbound = sc.inbound;
        TcpBandwidthPacer up = inbound.attr(SocksTcpFrontendRelayHandler.ATTR_UPLOAD_PACER).getAndSet(null);
        if (up != null) {
            up.close();
        }
        TcpBandwidthPacer dl = inbound.attr(SocksTcpFrontendRelayHandler.ATTR_DOWNLOAD_PACER).get();
        if (dl != null && inbound.isActive() && dl.deferEof(() -> Sockets.closeOnFlushed(inbound))) {
            return;
        }
        if (dl != null) {
            inbound.attr(SocksTcpFrontendRelayHandler.ATTR_DOWNLOAD_PACER).compareAndSet(dl, null);
            dl.close();
        }
        Sockets.closeOnFlushed(inbound);
    }

    private static void touchHalfCloseWatch(Channel inbound) {
        SocksTcpFrontendRelayHandler.HalfCloseWatch watch = inbound.attr(SocksTcpFrontendRelayHandler.HALF_CLOSE_WATCH).get();
        if (watch != null) {
            watch.touch();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        if (cause instanceof ProxyConnectException && Strings.lastIndexOf(cause.getMessage(), "timeout") != -1) {
            log.warn("TCP RELAY {}[{}] => {} thrown\n{}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause.getMessage());
        } else if (cause instanceof ProxyConnectException) {
            log.warn("TCP RELAY {}[{}] => {} proxy connect fail {}",
                    outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause.getMessage());
        } else {
            log.warn("TCP RELAY {}[{}] => {} thrown", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause);
        }
        Sockets.closeOnFlushed(outbound);
    }
}
