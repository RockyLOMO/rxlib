package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.core.App;
import org.rx.core.Cache;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;
import org.rx.security.AESUtil;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

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

        if (server.isAuthEnabled() && ProxyChannelManageHandler.get(inbound).isAnonymous()) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
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
        if (msg.type() == Socks5CommandType.CONNECT) {
            UnresolvedEndpoint destinationEndpoint = new UnresolvedEndpoint(dstAddr, msg.dstPort());
            Upstream upstream = server.router.invoke(destinationEndpoint);
            ReconnectingEventArgs e = new ReconnectingEventArgs(destinationEndpoint, upstream);
            connect(inbound, msg.dstAddrType(), e);
        } else if (msg.type() == Socks5CommandType.UDP_ASSOCIATE) {
            log.warn("Udp not impl");
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
        } else {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType dstAddrType, ReconnectingEventArgs e) {
        UnresolvedEndpoint destinationEndpoint = e.getUpstream().getEndpoint();
        String realHost = destinationEndpoint.getHost();
        if (server.support != null && !Sockets.isValidIp(realHost)) {
            App.logMetric(String.format("socks5[%s]_dstAddr", server.getConfig().getListenPort()), realHost);
            SUID hash = SUID.compute(realHost);
            quietly(() -> {
                Cache.getOrSet(hash, k -> {
                    server.support.fakeHost(hash, AESUtil.encryptToBase64(realHost));
                    return true;
                });
                destinationEndpoint.setHost(String.format("%s%s", hash, SocksSupport.FAKE_SUFFIX));
            });
        }
        SocketAddress finalDestinationAddress = destinationEndpoint.toSocketAddress();
        Sockets.bootstrap(inbound.channel().eventLoop(), server.getConfig(), channel -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            e.getUpstream().initChannel(channel);
        }).connect(finalDestinationAddress).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    if (!e.isCancel() && e.isChanged()) {
                        e.reset();
                        connect(inbound, dstAddrType, e);
                        return;
                    }
                }
                log.warn("socks5[{}] connect to backend {}[{}] fail", server.getConfig().getListenPort(), finalDestinationAddress, realHost, f.cause());
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            Channel outbound = f.channel();
            log.info("socks5[{}] {} connect to backend {}, destAddr={}[{}]", server.getConfig().getListenPort(),
                    inbound.channel(), outbound, finalDestinationAddress, realHost);
            ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
            outbound.pipeline().addLast(ForwardingBackendHandler.PIPELINE_NAME, new ForwardingBackendHandler(inbound, pendingPackages));
            inbound.pipeline().addLast(ForwardingFrontendHandler.PIPELINE_NAME, new ForwardingFrontendHandler(outbound, pendingPackages));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, dstAddrType));
        });
    }
}
