package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.core.ShellExecutor;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ChannelHandler.Sharable
public class Udp2rawServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Udp2rawServerHandler DEFAULT = new Udp2rawServerHandler();
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


    }
}
