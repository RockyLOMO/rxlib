package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.io.Compressible;
import org.rx.io.GZIPStream;
import org.rx.io.MemoryStream;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ChannelHandler.Sharable
public class Udp2rawHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Udp2rawHandler DEFAULT = new Udp2rawHandler();
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;
    //dst, src
    final Map<InetSocketAddress, InetSocketAddress> clientRoutes = new ConcurrentHashMap<>();
    @Setter
    int gzipMinLength = Compressible.MIN_LENGTH;

    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.server(inbound.channel());
        InetSocketAddress sourceEp = in.sender();

        List<InetSocketAddress> udp2rawServers = server.config.getUdp2rawServers();
        //client
        if (udp2rawServers != null) {
            if (!udp2rawServers.contains(sourceEp) && !clientRoutes.containsKey(sourceEp)) {
                inBuf.skipBytes(3);
                UnresolvedEndpoint destinationEp = UdpManager.decode(inBuf);
                RouteEventArgs routeArgs = new RouteEventArgs(sourceEp, destinationEp);
                server.raiseEvent(server.onUdpRoute, routeArgs);
                Upstream upstream = routeArgs.getValue();

                AuthenticEndpoint svrEp = upstream.getSocksServer();
                if (svrEp != null) {
                    ByteBuf outBuf = Bytes.directBuffer(64 + inBuf.readableBytes());
                    outBuf.writeShort(STREAM_MAGIC);
                    outBuf.writeByte(STREAM_VERSION);
                    UdpManager.encode(outBuf, new UnresolvedEndpoint(sourceEp));
                    UdpManager.encode(outBuf, destinationEp);
                    zip(outBuf, inBuf);

                    inbound.writeAndFlush(new DatagramPacket(outBuf, svrEp.getEndpoint()));
                    log.info("UDP2RAW CLIENT {} => {}[{}]", sourceEp, svrEp.getEndpoint(), destinationEp);
                    return;
                }

                UnresolvedEndpoint upDstEp = upstream.getDestination();
                inbound.writeAndFlush(new DatagramPacket(inBuf.retain(), upDstEp.socketAddress()));
                clientRoutes.put(upDstEp.socketAddress(), sourceEp);
                log.info("UDP2RAW CLIENT DIRECT {} => {}", sourceEp, upDstEp);
                return;
            }

            InetSocketAddress upSrcEp = clientRoutes.get(sourceEp);
            if (upSrcEp != null) {
                ByteBuf outBuf = UdpManager.socks5Encode(inBuf, new UnresolvedEndpoint(sourceEp));
                inbound.writeAndFlush(new DatagramPacket(outBuf, upSrcEp));
                log.info("UDP2RAW CLIENT DIRECT {} => {}", sourceEp, upSrcEp);
                return;
            }

            if (inBuf.readShort() != STREAM_MAGIC & inBuf.readByte() != STREAM_VERSION) {
                log.warn("discard {} bytes", inBuf.readableBytes());
                return;
            }
            UnresolvedEndpoint srcEp = UdpManager.decode(inBuf);
            UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
            ByteBuf outBuf = UdpManager.socks5Encode(inBuf, dstEp);
            inbound.writeAndFlush(new DatagramPacket(outBuf, srcEp.socketAddress()));
            log.info("UDP2RAW CLIENT {}[{}] => {}", sourceEp, dstEp, srcEp);
            return;
        }

        //server
        if (inBuf.readShort() != STREAM_MAGIC & inBuf.readByte() != STREAM_VERSION) {
            log.warn("discard {} bytes", inBuf.readableBytes());
            return;
        }
        UnresolvedEndpoint srcEp = UdpManager.decode(inBuf);
        UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(srcEp.socketAddress(), k -> {
            RouteEventArgs routeArgs = new RouteEventArgs(srcEp.socketAddress(), dstEp);
            server.raiseEvent(server.onUdpRoute, routeArgs);
            Upstream upstream = routeArgs.getValue();
            Channel channel = Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                SocksContext.server(outbound, server);
                upstream.initChannel(outbound);

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(SocksContext.udpSource(outbound));
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) {
                        ByteBuf outBuf = Bytes.directBuffer(64 + out.content().readableBytes());
                        outBuf.writeShort(STREAM_MAGIC);
                        outBuf.writeByte(STREAM_VERSION);
                        UdpManager.encode(outBuf, srcEp);
                        UdpManager.encode(outBuf, dstEp);
                        outBuf.writeBytes(out.content());
                        inbound.writeAndFlush(new DatagramPacket(outBuf, sourceEp));
//                        log.info("UDP2RAW SERVER {}[{}] => {}[{}]", out.sender(), dstEp, sourceEp, srcEp);
                    }
                });
            }).bind(0).addListener(Sockets.logBind(0)).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
            SocksContext.initPendingQueue(channel, srcEp.socketAddress(), dstEp);
            return new UdpManager.UdpChannelUpstream(channel, upstream);
        });

        ByteBuf outBuf = unzip(inBuf);
        DatagramPacket packet = new DatagramPacket(outBuf, dstEp.socketAddress());

        Channel outbound = outCtx.getChannel();
        UdpManager.pendOrWritePacket(outbound, packet);
//        log.info("UDP2RAW SERVER {}[{}] => {}", sourceEp, srcEp, dstEp);
    }

    private void zip(ByteBuf outBuf, ByteBuf inBuf) {
        if (inBuf.readableBytes() < gzipMinLength) {
            outBuf.writeByte(1);
            outBuf.writeBytes(inBuf);
            return;
        }
        outBuf.writeByte(2);
        int w = outBuf.writerIndex();
        int unzipLen = inBuf.readableBytes();
        try (GZIPStream stream = new GZIPStream(new MemoryStream(outBuf, true), true)) {
            stream.write(inBuf);
        }
        outBuf.readerIndex(0);
        log.info("UDP2RAW ZIP {} => {}", unzipLen, outBuf.writerIndex() - w);
    }

    private ByteBuf unzip(ByteBuf inBuf) {
        if (inBuf.readByte() == 1) {
            return inBuf.retain();
        }
        ByteBuf outBuf = Bytes.directBuffer(inBuf.readableBytes());
        try (GZIPStream stream = new GZIPStream(new MemoryStream(inBuf, false), true)) {
            stream.read(outBuf);
        }
        return outBuf;
    }
}
