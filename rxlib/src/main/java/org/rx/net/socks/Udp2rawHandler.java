package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
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
    final Map<InetSocketAddress, Tuple<InetSocketAddress, UnresolvedEndpoint>> clientRoutes = new ConcurrentHashMap<>();
    @Setter
    int gzipMinLength = Compressible.MIN_LENGTH;

    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.getParentAttr(inbound.channel(), SocksContext.SOCKS_SVR);
        final InetSocketAddress srcEp0 = in.sender();

        List<InetSocketAddress> udp2rawServers = server.config.getUdp2rawServers();
        //client
        if (udp2rawServers != null) {
            if (!udp2rawServers.contains(srcEp0) && !clientRoutes.containsKey(srcEp0)) {
                final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
                SocksContext e = new SocksContext(srcEp0, dstEp);
                server.raiseEvent(server.onUdpRoute, e);
                Upstream upstream = e.getUpstream();

                AuthenticEndpoint svrEp = upstream.getSocksServer();
                if (svrEp != null) {
                    ByteBuf outBuf = Bytes.directBuffer(64 + inBuf.readableBytes());
                    outBuf.writeShort(STREAM_MAGIC);
                    outBuf.writeByte(STREAM_VERSION);
                    UdpManager.encode(outBuf, new UnresolvedEndpoint(srcEp0));
                    UdpManager.encode(outBuf, dstEp);
                    zip(outBuf, inBuf);

                    inbound.writeAndFlush(new DatagramPacket(outBuf, svrEp.getEndpoint()));
//                    log.info("UDP2RAW CLIENT {} => {}[{}]", srcEp0, svrEp.getEndpoint(), dstEp);
                    return;
                }

                UnresolvedEndpoint upDstEp = upstream.getDestination();
                log.debug("UDP2RAW[{}] CLIENT DIRECT {} => {}[{}]", server.config.getListenPort(), srcEp0, upDstEp, dstEp);
                inbound.writeAndFlush(new DatagramPacket(inBuf.retain(), upDstEp.socketAddress()));
                clientRoutes.put(upDstEp.socketAddress(), Tuple.of(srcEp0, dstEp));
                return;
            }

            Tuple<InetSocketAddress, UnresolvedEndpoint> upSrcs = clientRoutes.get(srcEp0);
            if (upSrcs != null) {
                ByteBuf outBuf = UdpManager.socks5Encode(inBuf, upSrcs.right);
                log.debug("UDP2RAW[{}] CLIENT DIRECT {}[{}] => {}", server.config.getListenPort(), srcEp0, upSrcs.right, upSrcs.left);
                inbound.writeAndFlush(new DatagramPacket(outBuf, upSrcs.left));
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
//            log.info("UDP2RAW CLIENT {}[{}] => {}", srcEp0, dstEp, srcEp);
            return;
        }

        //server
        if (inBuf.readShort() != STREAM_MAGIC & inBuf.readByte() != STREAM_VERSION) {
            log.warn("discard {} bytes", inBuf.readableBytes());
            return;
        }
        final UnresolvedEndpoint srcEp = UdpManager.decode(inBuf);
        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        Channel outbound = UdpManager.openChannel(srcEp.socketAddress(), k -> {
            SocksContext e = new SocksContext(srcEp.socketAddress(), dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            return Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                        upstream.initChannel(ob);

//                ob.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
//                    @Override
//                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
////                        UdpManager.closeChannel(SocksContext.realSource(ob));
//                        return super.newIdleStateEvent(state, first);
//                    }
//                }, new SimpleChannelInboundHandler<DatagramPacket>() {
//                    @Override
//                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) {
//                        ByteBuf outBuf = Bytes.directBuffer(64 + out.content().readableBytes());
//                        outBuf.writeShort(STREAM_MAGIC);
//                        outBuf.writeByte(STREAM_VERSION);
//                        UdpManager.encode(outBuf, srcEp);
//                        UdpManager.encode(outBuf, dstEp);
//                        outBuf.writeBytes(out.content());
//                        inbound.writeAndFlush(new DatagramPacket(outBuf, srcEp0));
////                        log.info("UDP2RAW SERVER {}[{}] => {}[{}]", out.sender(), dstEp, srcEp0, srcEp);
//                    }
//                });
                    }).attr(SocksContext.SOCKS_SVR, server).bind(0).addListener(Sockets.logBind(0))
//                    .addListener(UdpManager.FLUSH_PENDING_QUEUE)
                    .channel()
//                    .syncUninterruptibly().channel(), srcEp.socketAddress(), dstEp, upstream, false)
                    ;
        });

        ByteBuf outBuf = unzip(inBuf);
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(outBuf, dstEp.socketAddress()));
//        log.info("UDP2RAW SERVER {}[{}] => {}", srcEp0, srcEp, dstEp);
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
