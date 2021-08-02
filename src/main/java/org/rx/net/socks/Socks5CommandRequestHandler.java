package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.core.StringBuilder;
import org.rx.net.AESCodec;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.TransportUtil;
import org.rx.net.socks.upstream.Socks5ProxyHandler;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@RequiredArgsConstructor
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    public static final Socks5CommandRequestHandler DEFAULT = new Socks5CommandRequestHandler();

    @SneakyThrows
    @Override
    protected void channelRead0(final ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) {
        ChannelPipeline pipeline = inbound.pipeline();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        SocksProxyServer server = inbound.attr(SocksProxyServer.CTX_SERVER).get();
        log.debug("socks5[{}] {} {}/{}:{}", server.getConfig().getListenPort(), msg.type(), msg.dstAddrType(), msg.dstAddr(), msg.dstPort());

        if (server.isAuthEnabled() && ProxyManageHandler.get(inbound).getUser().isAnonymous()) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(msg.dstAddr(), msg.dstPort());
        if (dstEp.getHost().endsWith(SocksSupport.FAKE_HOST_SUFFIX)) {
            UnresolvedEndpoint realEp = SocksSupport.fakeDict().get(SUID.valueOf(dstEp.getHost().substring(0, 22)));
            if (realEp == null) {
                log.error("socks5[{}] recover dstEp {} fail", server.getConfig().getListenPort(), dstEp);
            } else {
                log.info("socks5[{}] recover dstEp {}[{}]", server.getConfig().getListenPort(), dstEp, realEp);
                dstEp = realEp;
            }
        }

        if (msg.type() == Socks5CommandType.CONNECT) {
            Upstream upstream = server.router.invoke(dstEp);
            ReconnectingEventArgs e = new ReconnectingEventArgs(upstream);
            connect(inbound, msg.dstAddrType(), e);
        } else if (msg.type() == Socks5CommandType.UDP_ASSOCIATE) {
            log.info("UDP_ASSOCIATE {} => {}:{}", inbound.channel().remoteAddress(), msg.dstAddr(), msg.dstPort());
            InetSocketAddress bindEp = (InetSocketAddress) inbound.channel().localAddress();
            Socks5AddressType bindAddrType = bindEp.getAddress() instanceof Inet6Address ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4;
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS,
                    bindAddrType, bindEp.getHostString(), bindEp.getPort()));
        } else {
            log.warn("Command {} not support", msg.type());
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void connect(ChannelHandlerContext inbound, Socks5AddressType dstAddrType, ReconnectingEventArgs e) {
        SocksProxyServer server = inbound.attr(SocksProxyServer.CTX_SERVER).get();
        Upstream upstream = e.getUpstream();
        Sockets.bootstrap(inbound.channel().eventLoop(), server.getConfig(), upstream::initChannel)
                .connect(upstream.getEndpoint().toSocketAddress()).addListener((ChannelFutureListener) f -> {
            //initChannel 可能会变dstEp
            UnresolvedEndpoint destinationEp = upstream.getEndpoint();
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.raiseEvent(server.onReconnecting, e);
                    if (!e.isCancel() && e.isChanged()) {
                        e.reset();
                        connect(inbound, dstAddrType, e);
                        return;
                    }
                }
                log.error("socks5[{}] connect fail, dstEp={}[{}]", server.getConfig().getListenPort(),
                        destinationEp, e.getUpstream().getEndpoint(), f.cause());
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            Channel outbound = f.channel();
            StringBuilder aesMsg = new StringBuilder();
            Socks5ProxyHandler proxyHandler;
            SocksConfig config = server.getConfig();
            if (server.aesRouter(destinationEp) && (proxyHandler = outbound.pipeline().get(Socks5ProxyHandler.class)) != null) {
                proxyHandler.setHandshakeCallback(() -> {
                    if (config.getTransportFlags().has(TransportFlags.BACKEND_COMPRESS)) {
                        ChannelHandler[] handlers = new AESCodec(config.getAesKey()).channelHandlers();
                        for (int i = handlers.length - 1; i > -1; i--) {
                            ChannelHandler handler = handlers[i];
                            outbound.pipeline().addAfter(TransportUtil.ZIP_DECODER, handler.getClass().getSimpleName(), handler);
                        }
//                        aesMsg.append("[BACKEND_AES] %s", Strings.join(outbound.pipeline().names()));
                        aesMsg.append("[BACKEND_AES]");
                    }
                    relay(inbound, outbound, dstAddrType, destinationEp, e.getUpstream().getEndpoint(), aesMsg);
                });
                return;
            }

            relay(inbound, outbound, dstAddrType, destinationEp, e.getUpstream().getEndpoint(), aesMsg);
        });
    }

    private void relay(ChannelHandlerContext inbound, Channel outbound, Socks5AddressType dstAddrType,
                       UnresolvedEndpoint destinationEp, UnresolvedEndpoint realEp,
                       StringBuilder extMsg) {
        ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
        outbound.pipeline().addLast(ForwardingBackendHandler.PIPELINE_NAME, new ForwardingBackendHandler(inbound, pendingPackages));
        inbound.pipeline().addLast(ForwardingFrontendHandler.PIPELINE_NAME, new ForwardingFrontendHandler(outbound, pendingPackages));
        inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, dstAddrType)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                Sockets.closeOnFlushed(f.channel());
                return;
            }

            SocksProxyServer server = inbound.attr(SocksProxyServer.CTX_SERVER).get();
            SocksConfig config = server.getConfig();
            if (server.aesRouter(destinationEp) && config.getTransportFlags().has(TransportFlags.FRONTEND_COMPRESS)) {
                ChannelHandler[] handlers = new AESCodec(config.getAesKey()).channelHandlers();
                for (int i = handlers.length - 1; i > -1; i--) {
                    ChannelHandler handler = handlers[i];
                    inbound.pipeline().addAfter(TransportUtil.ZIP_DECODER, handler.getClass().getSimpleName(), handler);
                }
//                extMsg.append("[FRONTEND_AES] %s", Strings.join(inbound.channel().pipeline().names()));
                extMsg.append("[FRONTEND_AES]");
            }
            log.info("socks5[{}] {} => {} connected, dstEp={}[{}] {}", config.getListenPort(),
                    inbound.channel().localAddress(), outbound.remoteAddress(), destinationEp, realEp, extMsg.toString());

            SocksSupport.ENDPOINT_TRACER.link(inbound.channel(), outbound);
        });
    }
}
