package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.NQuery;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ChannelHandler.Sharable
public class Udp2rawClientHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Udp2rawClientHandler DEFAULT = new Udp2rawClientHandler();
    //dst, src
    static final Map<InetSocketAddress, Set<InetSocketAddress>> routeRules = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.server(ctx.channel());
        InetSocketAddress sourceEp = in.sender();
        inBuf.skipBytes(3);
        Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
        InetSocketAddress destinationEp = new InetSocketAddress(dstAddr, inBuf.readUnsignedShort());

        if (!NQuery.of(server.config.getUdp2rawServers()).any(p -> p.getAddress().equals(sourceEp.getAddress()))) {
            routeRules.computeIfAbsent(destinationEp, k -> ConcurrentHashMap.newKeySet()).add(sourceEp);
            inBuf.readerIndex(0);
            ctx.writeAndFlush(new DatagramPacket(inBuf, server.config.getUdp2rawServers().next()));
        } else {
            Set<InetSocketAddress> sourceEps = routeRules.get(destinationEp);
            if (!CollectionUtils.isEmpty(sourceEps)) {
                if (sourceEps.size() > 1) {
                    log.warn("Too many sources, dest={} sources={}", destinationEp, sourceEps);
                }
                for (InetSocketAddress ep : sourceEps) {
                    ctx.writeAndFlush(new DatagramPacket(inBuf.slice(), ep));
                }
            }
        }
    }
}
