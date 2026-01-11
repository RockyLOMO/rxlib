package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Udp2rawHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    @Sharable
    static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            SocksProxyServer server = Sockets.getAttr(outbound, SocksContext.SOCKS_SVR);
            SocksConfig config = server.config;
            SocksContext sc = SocksContext.ctx(outbound);
            InetSocketAddress udp2rawServer = sc.udp2rawServer;
            InetSocketAddress clientEp = sc.source;
//            UnresolvedEndpoint dstEp = sc.firstDestination;
            InetSocketAddress dstEp = out.sender();
            ByteBuf outBuf = out.content();
            if (sc.tryGetUdpSocksServer() != null) {
                outBuf.retain();
            } else {
                outBuf = UdpManager.socks5Encode(outBuf.retain(), dstEp);
            }

            ByteBufAllocator allocator = ctx.alloc();
            ByteBuf header = allocator.directBuffer(128);
            CompositeByteBuf outBufCom = allocator.compositeDirectBuffer(2);
            try {
                header.writeShort(STREAM_MAGIC);
                header.writeByte(STREAM_VERSION);
                UdpManager.encode(header, clientEp);
//                UdpManager.encode(header, dstEp);
                outBufCom.addComponents(true, header, outBuf);
                if (config.isDebug()) {
                    log.info("UDP2RAW[{}] server recv {} => {}[{}]", config.getListenPort(), dstEp, udp2rawServer, clientEp);
                }
                sc.inbound.writeAndFlush(new DatagramPacket(outBufCom, udp2rawServer));
            } catch (Exception e) {
                header.release();
                outBufCom.release();
                throw e;
            }
        }
    }

    public static final Udp2rawHandler DEFAULT = new Udp2rawHandler();
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        Channel inbound = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);

        InetSocketAddress udp2rawClient = server.config.getUdp2rawClient();
        //client
        if (udp2rawClient != null) {
            processClient(udp2rawClient, ctx, in);
            return;
        }

        //server
        processServer(ctx, in);
    }

    void processClient(InetSocketAddress udp2rawClient, ChannelHandlerContext ctx, DatagramPacket in) {
        Channel inbound = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        InetSocketAddress srcEp = in.sender();
        ByteBuf inBuf = in.content();

        //read client
        if (!udp2rawClient.equals(srcEp)) {
            InetSocketAddress clientEp = in.sender();
            final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
            SocksContext e = new SocksContext(clientEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            UnresolvedEndpoint upDstEp = upstream.getDestination();

            //忽略upstream.getSocksServer()
            ByteBufAllocator allocator = ctx.alloc();
            ByteBuf header = allocator.directBuffer(128);
            CompositeByteBuf outBuf = allocator.compositeDirectBuffer(2);
            try {
                header.writeShort(STREAM_MAGIC);
                header.writeByte(STREAM_VERSION);
                UdpManager.encode(header, clientEp);
                UdpManager.encode(header, upDstEp);
                outBuf.addComponents(true, header, inBuf.retain());
                if (config.isDebug()) {
                    log.info("UDP2RAW[{}] client send {} => {}[{}]", config.getListenPort(), clientEp, udp2rawClient, upDstEp);
                }
                inbound.writeAndFlush(new DatagramPacket(outBuf, udp2rawClient));
            } catch (Exception ex) {
                header.release();
                outBuf.release();
                throw ex;
            }
            return;
        }

        //write client
        if (inBuf.readShort() != STREAM_MAGIC & inBuf.readByte() != STREAM_VERSION) {
            log.warn("UDP2RAW[{}] client discard {} bytes", config.getListenPort(), inBuf.readableBytes());
            return;
        }
        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
//        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
//        log.info("UDP2RAW[{}] client recv {}[{}] => {}", config.getListenPort(), srcEp, dstEp, clientEp);
        if (config.isDebug()) {
            log.info("UDP2RAW[{}] client recv {} => {}", config.getListenPort(), srcEp, clientEp);
        }
        inbound.writeAndFlush(new DatagramPacket(inBuf.retain(), clientEp.socketAddress()));
    }

    void processServer(ChannelHandlerContext ctx, DatagramPacket in) {
        Channel inbound = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        InetSocketAddress srcEp = in.sender();
        ByteBuf inBuf = in.content();

        if (inBuf.readShort() != STREAM_MAGIC & inBuf.readByte() != STREAM_VERSION) {
            log.warn("UDP2RAW[{}] server discard {} bytes", config.getListenPort(), inBuf.readableBytes());
            return;
        }
        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        ChannelFuture outboundFuture = UdpManager.open(UdpManager.udp2rawRegion, clientEp.socketAddress(), k -> {
            SocksContext e = new SocksContext(clientEp.socketAddress(), dstEp);
            e.udp2rawServer = srcEp;
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
                upstream.initChannel(ob);
                ob.pipeline().addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()), UdpBackendRelayHandler.DEFAULT);
            }).attr(SocksContext.SOCKS_SVR, server).bind(0).addListener(Sockets.logBind(0));
            SocksContext.mark(inbound, chf, e);
            log.info("UDP2RAW[{}] server open {}", config.getListenPort(), k);
            chf.channel().closeFuture().addListener(f -> {
                log.info("UDP2RAW[{}] server close {}", config.getListenPort(), k);
                UdpManager.close(k);
            });
            return chf;
        });
        Channel outbound = outboundFuture.channel();

        SocksContext sc = SocksContext.ctx(outbound);
        UnresolvedEndpoint upDstEp;
        AuthenticEndpoint upSvrEp = sc.tryGetUdpSocksServer();
        if (upSvrEp != null) {
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
            inBuf.readerIndex(0);
        } else {
            upDstEp = dstEp;
        }
        inBuf.retain();
        if (sc.outboundActive) {
            if (config.isDebug()) {
                log.info("UDP2RAW[{}] server send {}[{}] => {}", config.getListenPort(), srcEp, clientEp, upDstEp);
            }
            outbound.writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
        } else {
            outboundFuture.addListener((ChannelFutureListener) f -> {
                if (config.isDebug()) {
                    log.info("UDP2RAW[{}] server outbound pending {}[{}] => {}", config.getListenPort(), srcEp, clientEp, upDstEp);
                }
                f.channel().writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
            });
        }
    }
}
