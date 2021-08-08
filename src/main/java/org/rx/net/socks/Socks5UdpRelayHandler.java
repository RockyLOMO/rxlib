package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Socks5UdpRelayHandler DEFAULT = new Socks5UdpRelayHandler();

    /**
     * https://datatracker.ietf.org/doc/html/rfc1928
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     *
     * @param inbound
     * @param in
     * @throws Exception
     */
    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

//        log.info("UDP[{}] bytes {}[{}]", in.recipient(), inBuf.readableBytes(), inBuf.readerIndex());
        SocksProxyServer server = SocksContext.server(inbound.channel());
        InetSocketAddress sourceEp = in.sender();
        inBuf.skipBytes(3);
        Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(dstAddr, inBuf.readUnsignedShort());

        UnresolvedEndpoint destinationEp = dstEp;
        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(sourceEp, k -> {
            Upstream upstream = server.udpRouter.invoke(destinationEp);
            return new UdpManager.UdpChannelUpstream(Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                SocksContext.server(outbound, server);
                SocksContext.udpSource(outbound, sourceEp);
                SocksContext.udpDestination(outbound, destinationEp.socketAddress());
                upstream.initChannel(outbound);

                outbound.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        InetSocketAddress destinationEp = SocksContext.udpDestination(outbound.channel());
                        Socks5AddressType outAddrType;
                        if (destinationEp.getAddress() instanceof Inet4Address) {
                            outAddrType = Socks5AddressType.IPv4;
                        } else if (destinationEp.getAddress() instanceof Inet6Address) {
                            outAddrType = Socks5AddressType.IPv6;
                        } else {
                            outAddrType = Socks5AddressType.DOMAIN;
                        }
                        ByteBuf outBuf = Bytes.directBuffer();
                        outBuf.writeZero(3);
                        outBuf.writeByte(outAddrType.byteValue());
                        Socks5AddressEncoder.DEFAULT.encodeAddress(outAddrType, destinationEp.getHostString(), outBuf);
                        outBuf.writeShort(destinationEp.getPort());
                        outBuf.writeBytes(out.content());
                        inbound.writeAndFlush(new DatagramPacket(outBuf, sourceEp));
                        log.info("UDP[{}] IN {}[{}] => {}", out.recipient(), out.sender(), destinationEp, sourceEp);
                    }
                });
            }).bind(Sockets.anyEndpoint(0)).addListener(Sockets.logBind(0)).sync().channel(), upstream);
        });

        AuthenticEndpoint svrEp = outCtx.getUpstream().getSocksServer();
        if (svrEp != null) {
            dstEp = new UnresolvedEndpoint(svrEp.getEndpoint());
            inBuf.readerIndex(0);
        }
        outCtx.getChannel().writeAndFlush(new DatagramPacket(inBuf, dstEp.socketAddress()).retain());
        log.info("UDP[{}] OUT {} => {}[{}]", in.recipient(), sourceEp, dstEp, destinationEp);
    }
}
