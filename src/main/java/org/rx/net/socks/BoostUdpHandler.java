//package org.rx.net.socks;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.channel.*;
//import io.netty.channel.socket.DatagramPacket;
//import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
//import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
//import io.netty.handler.codec.socksx.v5.Socks5AddressType;
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.io.Bytes;
//import org.rx.net.AuthenticEndpoint;
//import org.rx.net.Sockets;
//import org.rx.net.socks.upstream.Upstream;
//import org.rx.net.support.UnresolvedEndpoint;
//
//import java.net.Inet4Address;
//import java.net.Inet6Address;
//import java.net.InetAddress;
//import java.net.InetSocketAddress;
//
//@Slf4j
//@ChannelHandler.Sharable
//public class Socks5UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
//    short BOOST_MAGIC = -21266;
//    byte BOOST_DIRECT_FROM = 1, BOOST_DIRECT_TO = 2;
//
//    public static final Socks5UdpRelayHandler DEFAULT = new Socks5UdpRelayHandler();
//
//    /**
//     * https://datatracker.ietf.org/doc/html/rfc1928
//     * +----+------+------+----------+----------+----------+
//     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
//     * +----+------+------+----------+----------+----------+
//     * | 2  |  1   |  1   | Variable |    2     | Variable |
//     * +----+------+------+----------+----------+----------+
//     *
//     * @param inbound
//     * @param in
//     * @throws Exception
//     */
//    @SneakyThrows
//    @Override
//    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
//        ByteBuf inBuf = in.content();
//        if (inBuf.readableBytes() < 4) {
//            return;
//        }
//
//        SocksProxyServer server = SocksContext.server(inbound.channel());
//        InetSocketAddress sourceEp;
////        log.info("UDP[{}] bytes {}[{}]", in.recipient(), inBuf.readableBytes(), inBuf.readerIndex());
//        //skipBytes(3);
//        byte boostDirect;
//        if (inBuf.readShort() == BOOST_MAGIC && (boostDirect = inBuf.readByte()) > 0) {
//            byte[] addr = new byte[inBuf.readByte()];
//            inBuf.readBytes(addr);
//            sourceEp = new InetSocketAddress(InetAddress.getByAddress(addr), inBuf.readUnsignedShort());
//        } else {
//            sourceEp = in.sender();
//        }
//        Socks5AddressType addressType = Socks5AddressType.valueOf(inBuf.readByte());
//        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, inBuf);
//        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(dstAddr, inBuf.readUnsignedShort());
//
//        Upstream upstream = server.udpRouter.invoke(dstEp);
//        AuthenticEndpoint svrEp = upstream.getSocksServer();
//        if (svrEp != null) {
//            inBuf.readerIndex(3);
//
//            ByteBuf buf = Bytes.directBuffer(10 + inBuf.readableBytes());
//            buf.writeShort(BOOST_MAGIC);
//            buf.writeByte(BOOST_DIRECT_TO);
//            byte[] address = sourceEp.getAddress().getAddress();
//            buf.writeByte(address.length);
//            buf.writeBytes(address);
//            buf.writeShort(sourceEp.getPort());
//            buf.writeBytes(inBuf);
//            server.udpChannel.writeAndFlush(new DatagramPacket(buf, svrEp.getEndpoint()));
//            log.info("UDP[{}] BOOST OUT {} => {}[{}]", in.recipient(), sourceEp, dstEp, svrEp.getEndpoint());
//            return;
//        }
//
//        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(sourceEp, k -> {
//            return new UdpManager.UdpChannelUpstream(Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
//                SocksContext.server(outbound, server);
//                SocksContext.udpSource(outbound, sourceEp);
//                SocksContext.udpDestination(outbound, dstEp.socketAddress());
//                upstream.initChannel(outbound);
//
//                outbound.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
//                    @Override
//                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
//                        ByteBuf outBuf;
//                        if (upstream.getSocksServer() != null) {
//                            outBuf = out.content().retain();
//                        } else {
//                            InetSocketAddress destinationEp = SocksContext.udpDestination(outbound.channel());
//                            Socks5AddressType outAddrType;
//                            if (destinationEp.getAddress() instanceof Inet4Address) {
//                                outAddrType = Socks5AddressType.IPv4;
//                            } else if (destinationEp.getAddress() instanceof Inet6Address) {
//                                outAddrType = Socks5AddressType.IPv6;
//                            } else {
//                                outAddrType = Socks5AddressType.DOMAIN;
//                            }
//                            outBuf = Bytes.directBuffer();
//                            outBuf.writeZero(3);
//                            outBuf.writeByte(outAddrType.byteValue());
//                            Socks5AddressEncoder.DEFAULT.encodeAddress(outAddrType, destinationEp.getHostString(), outBuf);
//                            outBuf.writeShort(destinationEp.getPort());
//                            outBuf.writeBytes(out.content());
//                        }
//                        inbound.writeAndFlush(new DatagramPacket(outBuf, sourceEp));
//                        log.info("UDP[{}] IN {}[{}] => {}", out.recipient(), out.sender(), dstEp, sourceEp);
//                    }
//                });
//            }).bind(Sockets.anyEndpoint(0)).addListener(Sockets.logBind(0)).sync().channel(), upstream);
//        });
//        outCtx.getChannel().writeAndFlush(new DatagramPacket(inBuf, dstEp.socketAddress()).retain());
//        log.info("UDP[{}] OUT {} => {}[{}]", in.recipient(), sourceEp, dstEp, dstEp);
//    }
//}
