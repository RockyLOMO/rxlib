package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.io.Bytes;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ChannelHandler.Sharable
public class Udp2rawHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Udp2rawHandler DEFAULT = new Udp2rawHandler();
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;
    //dst, src
    final Map<InetSocketAddress, Set<InetSocketAddress>> clientRoutes = new ConcurrentHashMap<>();
    final Map<InetSocketAddress, Set<InetSocketAddress>> serverRoutes = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.server(ctx.channel());
        InetSocketAddress sourceEp = in.sender();

        RandomList<InetSocketAddress> udp2rawServers = server.config.getUdp2rawServers();
        if (udp2rawServers != null) {
            //client
            inBuf.skipBytes(3);
            Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
            String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
            InetSocketAddress destinationEp = new InetSocketAddress(dstAddr, inBuf.readUnsignedShort());

            if (!udp2rawServers.contains(sourceEp)) {
                clientRoutes.computeIfAbsent(destinationEp, k -> ConcurrentHashMap.newKeySet()).add(sourceEp);
                inBuf.readerIndex(0);
                inBuf.setShort(0, STREAM_MAGIC);
                inBuf.setByte(2, STREAM_VERSION);
                InetSocketAddress next = udp2rawServers.next();
                ctx.writeAndFlush(new DatagramPacket(inBuf, next).retain());
//                log.info("UDP2RAW CLIENT {} => {}[{}]", sourceEp, next, destinationEp);
            } else {
                inBuf.readerIndex(0);
                Set<InetSocketAddress> sourceEps = clientRoutes.get(destinationEp);
                if (sourceEps != null) {
                    if (sourceEps.size() > 1) {
                        log.warn("UDP2RAW CLIENT too many sources, dest={} sources={}", destinationEp, sourceEps);
                    }
                    for (InetSocketAddress ep : sourceEps) {
                        ctx.writeAndFlush(new DatagramPacket(inBuf.slice(), ep).retain());
//                        log.info("UDP2RAW CLIENT {} => {}", destinationEp, ep);
                    }
                }
            }
            return;
        }

        //server
        if (inBuf.readShort() == STREAM_MAGIC && inBuf.readByte() == STREAM_VERSION) {
            Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
            String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
            InetSocketAddress destinationEp = new InetSocketAddress(dstAddr, inBuf.readUnsignedShort());

            serverRoutes.computeIfAbsent(destinationEp, k -> ConcurrentHashMap.newKeySet()).add(sourceEp);
            ctx.writeAndFlush(new DatagramPacket(inBuf, destinationEp).retain());
//            log.info("UDP2RAW SERVER {} => {}", sourceEp, destinationEp);
        } else {
            inBuf.readerIndex(0);
            InetSocketAddress destinationEp = sourceEp;

            ByteBuf outBuf = Bytes.directBuffer(64 + inBuf.readableBytes());
            outBuf.writeZero(3);
            Socks5AddressType outAddrType = UdpManager.valueOf(destinationEp.getAddress());
            outBuf.writeByte(outAddrType.byteValue());
            Socks5AddressEncoder.DEFAULT.encodeAddress(outAddrType, destinationEp.getHostString(), outBuf);
            outBuf.writeShort(destinationEp.getPort());
            outBuf.writeBytes(inBuf);

            Set<InetSocketAddress> sourceEps = serverRoutes.get(destinationEp);
            if (sourceEps != null) {
                if (sourceEps.size() > 1) {
                    log.warn("UDP2RAW SERVER too many sources, dest={} sources={}", destinationEp, sourceEps);
                }
                for (InetSocketAddress ep : sourceEps) {
                    ctx.writeAndFlush(new DatagramPacket(outBuf.slice(), ep));
//                    log.info("UDP2RAW SERVER {} => {}", destinationEp, ep);
                }
            }
        }
    }
}
