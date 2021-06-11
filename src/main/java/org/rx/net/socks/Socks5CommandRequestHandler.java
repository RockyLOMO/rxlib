package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.socks.support.SocksSupport;
import org.rx.net.socks.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;
import org.rx.security.AESUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.rx.core.App.isNull;

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
        log.debug("socks5[{}] {} {}/{}:{}", server.getConfig().getListenPort(), msg.type(), msg.dstAddrType(), msg.dstAddr(), msg.dstPort());

        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String dstAddr = msg.dstAddr();
        if (dstAddr.startsWith(SocksSupport.FAKE_PREFIX)) {
            int s = SocksSupport.FAKE_PREFIX.length();
            String realHost = SocksSupport.FAKE_DICT.get(SUID.valueOf(dstAddr.substring(s, 22 + s)));
            if (realHost != null) {
                dstAddr = realHost;
                log.debug("recover dstAddr {}", dstAddr);
            }
        }

        UnresolvedEndpoint destinationAddress = new UnresolvedEndpoint(dstAddr, msg.dstPort());
        Upstream upstream = server.router.invoke(destinationAddress);
        ReconnectingEventArgs e = new ReconnectingEventArgs(destinationAddress, upstream);
        connect(inbound, msg.dstAddrType(), e);
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType addrType, ReconnectingEventArgs e) {
        UnresolvedEndpoint destinationAddress = isNull(e.getUpstream().getAddress(), e.getDestinationAddress());
        if (server.support != null) {
            String realHost = destinationAddress.getHost();
            log.debug("fake dstAddr {}", realHost);
            SUID hash = SUID.compute(realHost);
            server.support.fakeHost(hash, AESUtil.encryptToBase64(realHost, AESUtil.dailyKey()));
            destinationAddress.setHost(String.format("%s%s.f-li.cn", SocksSupport.FAKE_PREFIX, hash));
        }
        SocketAddress finalDestinationAddress = destinationAddress.toSocketAddress();
        Sockets.bootstrap(inbound.channel().eventLoop(), MemoryMode.LOW, channel -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            e.getUpstream().initChannel(channel);
        }).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, server.getConfig().getConnectTimeoutMillis())
                .connect(finalDestinationAddress).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    if (!e.isCancel() && e.isChanged()) {
                        e.reset();
                        connect(inbound, addrType, e);
                        return;
                    }
                }
                log.debug("socks5[{}] connect to backend {} fail", server.getConfig().getListenPort(), finalDestinationAddress, f.cause());
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, addrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            log.debug("socks5[{}] connect to backend {}", server.getConfig().getListenPort(), finalDestinationAddress);
            Channel outbound = f.channel();
//            Sockets.writeAndFlush(outbound, e.getUpstream().getPendingPackages());
            outbound.pipeline().addLast("from-upstream", new ForwardingBackendHandler(inbound));
            inbound.pipeline().addLast("to-upstream", new ForwardingFrontendHandler(outbound));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
        });
    }
}
