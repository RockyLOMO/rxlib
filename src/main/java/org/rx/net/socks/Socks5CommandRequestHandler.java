package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.DirectUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.BiFunc;
import org.rx.util.function.TripleFunc;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.rx.core.App.sneakyInvoke;

@Slf4j
@RequiredArgsConstructor
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    private final SocksProxyServer socksProxyServer;

    @SneakyThrows
    @Override
    protected void channelRead0(final ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) throws Exception {
        ChannelPipeline pipeline = inbound.pipeline();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        log.debug("socks5 read: {},{}:{}", msg.type(), msg.dstAddr(), msg.dstPort());

        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        InetSocketAddress dstAddr = InetSocketAddress.createUnresolved(msg.dstAddr(), msg.dstPort());
        BiFunc<SocketAddress, Upstream> upstreamSupplier = socksProxyServer.getConfig().getUpstreamSupplier();
        Upstream upstream = upstreamSupplier == null ? new DirectUpstream() : upstreamSupplier.invoke(dstAddr);
        connect(inbound, msg.dstAddrType(), upstream, dstAddr);
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType addrType, Upstream upstream, SocketAddress dstAddr) {
        Sockets.bootstrap(true, inbound.channel(), null, channel -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            //ProxyChannelManageHandler.get(inbound).getUsername()
            upstream.initChannel(channel);
        }).connect(dstAddr).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                TripleFunc<SocketAddress, Upstream, Upstream> upstreamPreReconnect = socksProxyServer.getConfig().getUpstreamPreReconnect();
                Upstream newUpstream;
                if (upstreamPreReconnect != null && (newUpstream = sneakyInvoke(() -> upstreamPreReconnect.invoke(dstAddr, upstream))) != null) {
                    connect(inbound, addrType, newUpstream, dstAddr);
                    return;
                }
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, addrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            log.trace("socks5 connect to backend {}", dstAddr);
            Channel outbound = f.channel();
            outbound.pipeline().addLast("from-upstream", new ForwardingBackendHandler(inbound));
            inbound.pipeline().addLast("to-upstream", new ForwardingFrontendHandler(outbound));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
        });
    }
}
