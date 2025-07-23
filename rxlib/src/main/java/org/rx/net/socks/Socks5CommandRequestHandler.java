package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.StringBuilder;
import org.rx.core.Tasks;
import org.rx.net.*;
import org.rx.net.socks.upstream.Socks5ProxyHandler;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.math.BigInteger;
import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    public static final Socks5CommandRequestHandler DEFAULT = new Socks5CommandRequestHandler();

    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) {
        ChannelPipeline pipeline = inbound.pipeline();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        SocksProxyServer server = Sockets.getAttr(inbound.channel(), SocksContext.SOCKS_SVR);
//        log.debug("socks5[{}] {} {}/{}:{}", server.getConfig().getListenPort(), msg.type(), msg.dstAddrType(), msg.dstAddr(), msg.dstPort());

        if (server.isAuthEnabled() && ProxyManageHandler.get(inbound).getUser().isAnonymous()) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(msg.dstAddr(), msg.dstPort());
        String dstEpHost = dstEp.getHost();
        if (dstEpHost.endsWith(SocksSupport.FAKE_HOST_SUFFIX)) {
            UnresolvedEndpoint realEp = SocksSupport.fakeDict().get(new BigInteger(dstEpHost.substring(0, dstEpHost.length() - SocksSupport.FAKE_HOST_SUFFIX.length())));
            if (realEp == null) {
                log.error("socks5[{}] recover dstEp {} fail", server.getConfig().getListenPort(), dstEp);
            } else {
                log.info("socks5[{}] recover dstEp {}[{}]", server.getConfig().getListenPort(), dstEp, realEp);
                dstEp = realEp;
            }
        }

        if (msg.type() == Socks5CommandType.CONNECT) {
            SocksContext e = new SocksContext((InetSocketAddress) inbound.channel().remoteAddress(), dstEp);
            server.raiseEvent(server.onRoute, e);
            connect(inbound.channel(), msg.dstAddrType(), e);
        } else if (msg.type() == Socks5CommandType.UDP_ASSOCIATE) {
            log.info("socks5[{}] UdpAssociate {}", server.getConfig().getListenPort(), msg);
            pipeline.remove(ProxyChannelIdleHandler.class.getSimpleName());
            pipeline.addLast(new Socks5UdpAssociateHandler(Tasks.setTimeout(() -> {
                log.info("UdpAssociate close by maxLife, tcp:{}", inbound.channel());
                Sockets.closeOnFlushed(inbound.channel());
            }, server.config.getUdpAssociateMaxLifeSeconds() * 1000L)));

            InetSocketAddress bindEp = (InetSocketAddress) inbound.channel().localAddress();
//            Socks5AddressType bindAddrType = bindEp.getAddress() instanceof Inet6Address ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4;
            Socks5AddressType bindAddrType = msg.dstAddrType();
            // msg.dstAddr(), msg.dstPort() = 0.0.0.0:0
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, bindAddrType, bindEp.getHostString(), bindEp.getPort()));
        } else {
            log.warn("Command {} not support", msg.type());
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void connect(Channel inbound, Socks5AddressType dstAddrType, SocksContext e) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);

        Sockets.bootstrap(inbound.eventLoop(), server.getConfig(), outbound -> {
            e.getUpstream().initChannel(outbound);

            SocksContext.mark(inbound, outbound, e, true);
            inbound.pipeline().addLast(FrontendRelayHandler.DEFAULT);
        }).attr(SocksContext.SOCKS_SVR, server).connect(e.getUpstream().getDestination().socketAddress()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    if (!e.isCancel() && e.isUpstreamChanged()) {
                        e.reset();
                        connect(inbound, dstAddrType, e);
                        return;
                    }
                }
                if (f.cause() instanceof io.netty.channel.ConnectTimeoutException) {
                    log.warn("socks5[{}] connect {}[{}] fail\n{}", server.getConfig().getListenPort(), e.getUpstream().getDestination(), e.firstDestination, f.cause().getMessage());
                } else {
                    log.error("socks5[{}] connect {}[{}] fail", server.getConfig().getListenPort(), e.getUpstream().getDestination(), e.firstDestination, f.cause());
                }
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            Channel outbound = f.channel();
            SocksSupport.ENDPOINT_TRACER.link(inbound, outbound);
            StringBuilder exMsg = new StringBuilder();
            Socks5ProxyHandler proxyHandler;
            SocksConfig config = server.getConfig();
            if (server.cipherRoute(e.firstDestination) && (proxyHandler = outbound.pipeline().get(Socks5ProxyHandler.class)) != null) {
                proxyHandler.setHandshakeCallback(() -> {
                    if (config.getTransportFlags().has(TransportFlags.CLIENT_COMPRESS_BOTH)) {
                        //todo 解依赖ZIP
                        outbound.attr(SocketConfig.ATTR_CONF).set(config);
                        ChannelHandler[] handlers = new CipherDecoder().channelHandlers();
                        for (int i = handlers.length - 1; i > -1; i--) {
                            ChannelHandler handler = handlers[i];
                            outbound.pipeline().addAfter(Sockets.ZIP_DECODER, handler.getClass().getSimpleName(), handler);
                        }
                        handlers = CipherEncoder.DEFAULT.channelHandlers();
                        for (int i = handlers.length - 1; i > -1; i--) {
                            ChannelHandler handler = handlers[i];
                            outbound.pipeline().addAfter(Sockets.ZIP_ENCODER, handler.getClass().getSimpleName(), handler);
                        }
                        exMsg.append("[BACKEND_CIPHER]");
                    }
                    relay(inbound, outbound, dstAddrType, e, exMsg);
                });
                return;
            }

            relay(inbound, outbound, dstAddrType, e, exMsg);
        });
    }

    private void relay(Channel inbound, Channel outbound, Socks5AddressType dstAddrType, SocksContext e, StringBuilder exMsg) {
        //initChannel may change dstEp
        UnresolvedEndpoint dstEp = e.getUpstream().getDestination();
        outbound.pipeline().addLast(BackendRelayHandler.DEFAULT);

        inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, dstAddrType)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                Sockets.closeOnFlushed(f.channel());
                return;
            }

            SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
            SocksConfig config = server.getConfig();
            if (server.cipherRoute(e.firstDestination) && config.getTransportFlags().has(TransportFlags.SERVER_COMPRESS_BOTH)) {
                outbound.attr(SocketConfig.ATTR_CONF).set(config);
                ChannelHandler[] handlers = new CipherDecoder().channelHandlers();
                for (int i = handlers.length - 1; i > -1; i--) {
                    ChannelHandler handler = handlers[i];
                    outbound.pipeline().addAfter(Sockets.ZIP_DECODER, handler.getClass().getSimpleName(), handler);
                }
                handlers = CipherEncoder.DEFAULT.channelHandlers();
                for (int i = handlers.length - 1; i > -1; i--) {
                    ChannelHandler handler = handlers[i];
                    outbound.pipeline().addAfter(Sockets.ZIP_ENCODER, handler.getClass().getSimpleName(), handler);
                }
                exMsg.append("[FRONTEND_CIPHER]");
            }
            log.info("socks5[{}] {} => {} connected, dstEp={}[{}] {}", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress(), dstEp, e.firstDestination, exMsg.toString());
        });
    }
}
