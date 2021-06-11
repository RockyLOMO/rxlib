package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.NetUtil;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.socks.support.SocksSupport;
import org.rx.net.socks.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;
import org.rx.security.AESUtil;

import java.net.SocketAddress;

import static org.rx.core.App.isNull;
import static org.rx.core.App.quietly;

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

        if (!msg.type().equals(Socks5CommandType.CONNECT)
                || (server.isAuthEnabled() && ProxyChannelManageHandler.get(inbound).isAnonymous())) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String dstAddr = msg.dstAddr();
        if (dstAddr.endsWith(SocksSupport.FAKE_SUFFIX)) {
            String realHost = SocksSupport.HOST_DICT.get(SUID.valueOf(dstAddr.substring(0, 22)));
            if (realHost != null) {
                dstAddr = realHost;
                log.info("socks5[{}] recover dstAddr {}", server.getConfig().getListenPort(), dstAddr);
            }
        }

        UnresolvedEndpoint destinationEndpoint = new UnresolvedEndpoint(dstAddr, msg.dstPort());
        Upstream upstream = server.router.invoke(destinationEndpoint);
        ReconnectingEventArgs e = new ReconnectingEventArgs(destinationEndpoint, upstream);
        connect(inbound, msg.dstAddrType(), e);
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType addrType, ReconnectingEventArgs e) {
        UnresolvedEndpoint destinationEndpoint = isNull(e.getUpstream().getEndpoint(), e.getDestinationEndpoint());
        String realHost = destinationEndpoint.getHost();
        if (server.support != null && !NetUtil.isValidIpV4Address(realHost)) {
            log.info("socks5[{}] fake dstAddr {}", server.getConfig().getListenPort(), realHost);
            SUID hash = SUID.compute(realHost);
            quietly(() -> {
                server.support.fakeHost(hash, AESUtil.encryptToBase64(realHost));
                destinationEndpoint.setHost(String.format("%s%s", hash, SocksSupport.FAKE_SUFFIX));
            });
        }
        SocketAddress finalDestinationAddress = destinationEndpoint.toSocketAddress();
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
            Channel outbound = f.channel();
            log.debug("socks5[{}] {} connect to backend {}, destAddr={}", server.getConfig().getListenPort(),
                    inbound.channel(), outbound, finalDestinationAddress);
//            Sockets.writeAndFlush(outbound, e.getUpstream().getPendingPackages());
            outbound.pipeline().addLast("from-upstream", new ForwardingBackendHandler(inbound));
            inbound.pipeline().addLast("to-upstream", new ForwardingFrontendHandler(outbound));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addrType));
        });
    }
}
