package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.ApplicationException;
import org.rx.io.Bytes;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Socks5UdpRelayHandler DEFAULT = new Socks5UdpRelayHandler();
    static final int CHANNEL_TIMEOUT = 60 * 8;
    static final Map<InetSocketAddress, Channel> HOLD = new ConcurrentHashMap<>();

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
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = inbound.attr(SocksProxyServer.CTX_SERVER).get();
        InetSocketAddress inRemoteEp = in.sender();
        inBuf.skipBytes(3);
        Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
        InetSocketAddress dstEp = new InetSocketAddress(dstAddr, inBuf.readUnsignedShort());

        Channel outbound = HOLD.computeIfAbsent(inRemoteEp, k -> {
            try {
                return Sockets.udpBootstrap(false, server.config.getMemoryMode()).handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        TransportUtil.addBackendHandler(channel, server.config, dstEp);
                        pipeline.addLast(
                                new IdleStateHandler(0, 0, CHANNEL_TIMEOUT),
                                ProxyChannelIdleHandler.DEFAULT,
                                new SimpleChannelInboundHandler<DatagramPacket>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                                        InetSocketAddress outRemoteEp = out.sender();
                                        Socks5AddressType outAddrType;
                                        if (outRemoteEp.getAddress() instanceof Inet4Address) {
                                            outAddrType = Socks5AddressType.IPv4;
                                        } else if (outRemoteEp.getAddress() instanceof Inet6Address) {
                                            outAddrType = Socks5AddressType.IPv6;
                                        } else {
                                            outAddrType = Socks5AddressType.DOMAIN;
                                        }
                                        ByteBuf outBuf = Bytes.directBuffer();
                                        outBuf.writeZero(3);
                                        outBuf.writeByte(outAddrType.byteValue());
                                        Socks5AddressEncoder.DEFAULT.encodeAddress(outAddrType, outRemoteEp.getHostString(), outBuf);
                                        outBuf.writeShort(outRemoteEp.getPort());
                                        outBuf.writeBytes(out.content());
                                        inbound.writeAndFlush(new DatagramPacket(outBuf, inRemoteEp));
                                        log.info("UDP[{}] IN {} => {}", out.recipient(), outRemoteEp, inRemoteEp);
                                    }
                                });
                    }
                }).bind(0).addListener(Sockets.logBind(0)).sync().channel();
            } catch (InterruptedException e) {
                throw ApplicationException.sneaky(e);
            }
        });
        outbound.writeAndFlush(new DatagramPacket(inBuf, dstEp).retain());
        log.info("UDP[{}] OUT {} => {}", in.recipient(), inRemoteEp, dstEp);
    }
}
