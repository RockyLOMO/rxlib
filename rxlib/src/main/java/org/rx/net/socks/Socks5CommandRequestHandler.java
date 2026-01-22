package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.*;
import org.rx.net.socks.upstream.Socks5ClientHandler;
import org.rx.net.support.UnresolvedEndpoint;

import java.math.BigInteger;
import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    public static final Socks5CommandRequestHandler DEFAULT = new Socks5CommandRequestHandler();
    static final DefaultSocks5CommandResponse SUCCESS_IPv4 = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4);
    static final DefaultSocks5CommandResponse SUCCESS_DOMAIN = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN);
    static final DefaultSocks5CommandResponse SUCCESS_IPv6 = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6);

    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) {
        ChannelPipeline pipeline = inbound.pipeline();
        Channel inCh = inbound.channel();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        SocksProxyServer server = Sockets.getAttr(inCh, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
//        log.debug("socks5[{}] {} {}/{}:{}", server.getConfig().getListenPort(), msg.type(), msg.dstAddrType(), msg.dstAddr(), msg.dstPort());

        if (server.isAuthEnabled() && ProxyManageHandler.get(inbound).getUser().isAnonymous()) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(msg.dstAddr(), msg.dstPort());
        String dstEpHost = dstEp.getHost();
        if (dstEpHost.endsWith(SocksRpcContract.FAKE_HOST_SUFFIX)) {
            UnresolvedEndpoint realEp = SocksRpcContract.fakeDict().get(new BigInteger(dstEpHost.substring(0, dstEpHost.length() - SocksRpcContract.FAKE_HOST_SUFFIX.length())));
            if (realEp == null) {
                log.error("socks5[{}] recover dstEp {} fail", config.getListenPort(), dstEp);
            } else {
                if (config.isDebug()) {
                    log.info("socks5[{}] recover dstEp {}[{}]", config.getListenPort(), dstEp, realEp);
                }
                dstEp = realEp;
            }
        }

        InetSocketAddress srcEp = (InetSocketAddress) inCh.remoteAddress();
        if (msg.type() == Socks5CommandType.CONNECT) {
            SocksContext e = SocksContext.getCtx(srcEp, dstEp);
            server.raiseEvent(server.onTcpRoute, e);
            connect(inCh, msg.dstAddrType(), e, null);
        } else if (msg.type() == Socks5CommandType.UDP_ASSOCIATE) {
            log.debug("socks5[{}] UDP associate {}", config.getListenPort(), msg);
            pipeline.remove(ProxyChannelIdleHandler.class.getSimpleName());

            Socks5AddressType bindAddrType = msg.dstAddrType();
            //msg.dstAddr(), msg.dstPort() = 0.0.0.0:0 客户端希望绑定
            //ipv4 udp svr 单个udp性能高
            InetSocketAddress bindEp = (InetSocketAddress) inCh.localAddress();
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, bindAddrType, bindEp.getHostString(), bindEp.getPort()));
        } else {
            log.warn("Command {} not support", msg.type());
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void connect(Channel inbound, Socks5AddressType dstAddrType, SocksContext e, short[] reconnectionAttempts) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;

        //tcp reconnect upstream可能会变，不定临时变量
        ChannelFuture outboundFuture = Sockets.bootstrap(inbound.eventLoop(), e.getUpstream().getConfig(), outbound -> {
            e.getUpstream().initChannel(outbound);
            inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        }).attr(SocksContext.SOCKS_SVR, server).connect(e.getUpstream().getDestination().socketAddress()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    short[] attempts = reconnectionAttempts;
                    if (attempts == null) {
                        attempts = new short[]{0};
                    }
                    if (!e.isCancel() && attempts[0] < 16) {
                        attempts[0]++;
                        connect(inbound, dstAddrType, e, attempts);
                        return;
                    }
                }
                if (f.cause() instanceof ConnectTimeoutException) {
                    log.warn("socks5[{}] TCP connect {}[{}] fail\n{}", config.getListenPort(), e.getUpstream().getDestination(), e.getFirstDestination(), f.cause().getMessage());
                } else {
                    log.error("socks5[{}] TCP connect {}[{}] fail", config.getListenPort(), e.getUpstream().getDestination(), e.getFirstDestination(), f.cause());
                }
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            Channel outbound = f.channel();
            SocksRpcContract.ENDPOINT_TRACER.link(inbound, outbound);
            Socks5ClientHandler proxyHandler;
            if (server.cipherRoute(e.getFirstDestination()) && (proxyHandler = outbound.pipeline().get(Socks5ClientHandler.class)) != null) {
                proxyHandler.setHandshakeCallback(() -> {
                    SocketConfig upConf = e.getUpstream().getConfig();
                    if (upConf.getTransportFlags().has(TransportFlags.COMPRESS_BOTH)) {
                        //todo 解依赖ZIP
                        outbound.attr(SocketConfig.ATTR_CONF).set(upConf);
                        Sockets.addBefore(outbound.pipeline(), Sockets.ZIP_DECODER, new CipherDecoder().channelHandlers());
                        Sockets.addBefore(outbound.pipeline(), Sockets.ZIP_ENCODER, CipherEncoder.DEFAULT.channelHandlers());
                        if (config.isDebug()) {
                            log.info("socks5[{}] TCP {} => {} BACKEND_CIPHER", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress());
                        }
                    }
                    relay(inbound, outbound, dstAddrType, e);
                });
                return;
            }

            relay(inbound, outbound, dstAddrType, e);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);
    }

    private void relay(Channel inbound, Channel outbound, Socks5AddressType dstAddrType, SocksContext e) {
        //initChannel may change dstEp
        UnresolvedEndpoint dstEp = e.getUpstream().getDestination();
        outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);

        DefaultSocks5CommandResponse commandResponse;
        switch (dstAddrType.byteValue()) {
            case 1:
                commandResponse = SUCCESS_IPv4;
                break;
            case 3:
                commandResponse = SUCCESS_DOMAIN;
                break;
            case 4:
                commandResponse = SUCCESS_IPv6;
                break;
            default:
                commandResponse = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, dstAddrType);
                break;
        }
        inbound.writeAndFlush(commandResponse).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                Sockets.closeOnFlushed(f.channel());
                return;
            }

            SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
            SocksConfig config = server.getConfig();
            if (server.cipherRoute(e.getFirstDestination()) && config.getTransportFlags().has(TransportFlags.COMPRESS_BOTH)) {
                inbound.attr(SocketConfig.ATTR_CONF).set(config);
                Sockets.addBefore(inbound.pipeline(), Sockets.ZIP_DECODER, new CipherDecoder().channelHandlers());
                Sockets.addBefore(inbound.pipeline(), Sockets.ZIP_ENCODER, CipherEncoder.DEFAULT.channelHandlers());
                if (config.isDebug()) {
                    log.info("socks5[{}] TCP {} => {} FRONTEND_CIPHER", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress());
                }
            }
            log.info("socks5[{}] TCP {} => {} connected, dstEp={}[{}]", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress(), dstEp, e.getFirstDestination());
        });
    }
}
