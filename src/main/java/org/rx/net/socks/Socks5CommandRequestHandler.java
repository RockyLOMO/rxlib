package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.proxy.Socks5ProxyHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    @Override
    protected void channelRead0(final ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) throws Exception {
        log.debug("socks5 read: {},{}:{}", msg.type(), msg.dstAddr(), msg.dstPort());
        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            inbound.fireChannelRead(msg);
            return;
        }
        Sockets.bootstrap(true, inbound.channel(), null, ch -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            ch.pipeline().addLast(new BackendHandler(inbound));
        }).connect(msg.dstAddr(), msg.dstPort()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, msg.dstAddrType()));
                return;
            }
            log.trace("socks5 connect to backend {}:{}", msg.dstAddr(), msg.dstPort());
            inbound.pipeline().addLast(new FrontendHandler(f.channel()));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, msg.dstAddrType()));
        });
    }

    @RequiredArgsConstructor
    private static class BackendHandler extends ChannelInboundHandlerAdapter {
        private final ChannelHandlerContext inbound;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!inbound.channel().isActive()) {
                return;
            }
            inbound.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx2) throws Exception {
            if (!inbound.channel().isActive()) {
                return;
            }
            Sockets.closeOnFlushed(inbound.channel());
        }
    }

    @RequiredArgsConstructor
    private static class FrontendHandler extends ChannelInboundHandlerAdapter {
        private final Channel outbound;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!outbound.isActive()) {
                return;
            }
            outbound.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (!outbound.isActive()) {
                return;
            }
            Sockets.closeOnFlushed(outbound);
        }
    }
}
