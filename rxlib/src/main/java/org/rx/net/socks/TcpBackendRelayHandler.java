package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class TcpBackendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final TcpBackendRelayHandler DEFAULT = new TcpBackendRelayHandler();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//        Channel outbound = ctx.channel();
//        SocksContext sc = SocksContext.ctx(outbound);
//        ConcurrentLinkedQueue<Object> pending = sc.pendingPackages;
//        if (CollectionUtils.isEmpty(pending)) {
//            return;
//        }
//        log.info("TCP outbound pending FLUSH_PACKS {} => {}[{}]", sc.inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
//        Sockets.writeAndFlush(outbound, pending);
//        sc.pendingPackages = null;
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        log.debug("RELAY {}[{}] => {}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress());
        sc.inbound.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocksContext sc = SocksContext.ctx(ctx.channel());
        super.channelInactive(ctx);
        Sockets.closeOnFlushed(sc.inbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        if (cause instanceof ProxyConnectException && Strings.lastIndexOf(cause.getMessage(), "timeout") != -1) {
            log.warn("RELAY {}[{}] => {} thrown\n{}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause.getMessage());
        } else {
            log.warn("RELAY {}[{}] => {} thrown", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause);
        }
        Sockets.closeOnFlushed(outbound);
    }
}
