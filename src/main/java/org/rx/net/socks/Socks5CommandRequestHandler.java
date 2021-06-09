package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    final SocksProxyServer server;

    @SneakyThrows
    @Override
    protected void channelRead0(final ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) {
        ChannelPipeline pipeline = inbound.pipeline();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        log.debug("socks5 read: {},{}:{}", msg.type(), msg.dstAddr(), msg.dstPort());

        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        InetSocketAddress dstAddr = InetSocketAddress.createUnresolved(msg.dstAddr(), msg.dstPort());
        Upstream upstream = server.router.invoke(dstAddr);
        ReconnectingEventArgs e = new ReconnectingEventArgs(dstAddr, upstream);
        connect(inbound, msg.dstAddrType(), e);
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType addrType, ReconnectingEventArgs e) {
        Sockets.bootstrap(inbound.channel().eventLoop(), MemoryMode.LOW, channel -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            e.getUpstream().initChannel(channel);
        }).connect(e.getRemoteAddress()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    if (!e.isCancel() && e.isChanged()) {
                        e.reset();
                        connect(inbound, addrType, e);
                        return;
                    }
                }
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, addrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            log.trace("socks5 connect to backend {}", e.getRemoteAddress());
            Channel outbound = f.channel();
            outbound.pipeline().addLast("from-upstream", new ForwardingBackendHandler(inbound));
            inbound.pipeline().addLast("to-upstream", new ForwardingFrontendHandler(outbound));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
        });
    }
}
