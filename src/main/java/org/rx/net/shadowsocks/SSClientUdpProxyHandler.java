package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socks.SocksAddressType;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.CryptoFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

@RequiredArgsConstructor
public class SSClientUdpProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    static final byte[] SOCKS5_ADDRESS_PREFIX = new byte[]{0, 0, 0};
    final ShadowsocksConfig config;

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, DatagramPacket msg) throws Exception {
        InetSocketAddress clientSender = msg.sender();

        msg.content().skipBytes(3);//skip [5, 0, 0]
        SSAddressRequest addrRequest = SSAddressRequest.decode(msg.content());
        InetSocketAddress clientRecipient = new InetSocketAddress(addrRequest.host(), addrRequest.port());
        proxy(clientSender, msg.content(), clientRecipient, clientCtx);
    }

    @SneakyThrows
    private void proxy(InetSocketAddress clientSender, ByteBuf msg, InetSocketAddress clientRecipient, ChannelHandlerContext clientCtx) {
//        Channel udpChannel = NatMapper.getChannel(clientSender, k -> Sockets.udpBootstrap().handler(new ChannelInitializer<Channel>() {
//            @Override
//            protected void initChannel(Channel ch) throws Exception {
//                ch.attr(SSCommon.IS_UDP).set(true);
//                ch.attr(SSCommon.CIPHER).set(CryptoFactory.get(config.getMethod(), config.getPassword(), true));
//                ch.pipeline().addLast(new CipherCodec(), new ProtocolCodec(true),
//                        new SimpleChannelInboundHandler<DatagramPacket>() {
//                            @Override
//                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
//                                InetSocketAddress sAddr = ctx.channel().attr(SSCommon.REMOTE_SRC).get();
//                                SSAddressRequest addrRequest;
//                                if (sAddr.getAddress() instanceof Inet6Address) {
//                                    addrRequest = new SSAddressRequest(SocksAddressType.IPv6, sAddr.getHostString(), sAddr.getPort());
//                                } else if (sAddr.getAddress() instanceof Inet4Address) {
//                                    addrRequest = new SSAddressRequest(SocksAddressType.IPv4, sAddr.getHostString(), sAddr.getPort());
//                                } else {
//                                    addrRequest = new SSAddressRequest(SocksAddressType.DOMAIN, sAddr.getHostString(), sAddr.getPort());
//                                }
//
//                                //add socks5 udp  prefixed address
//                                ByteBuf addrBuff = Unpooled.buffer(128);
//                                addrBuff.writeBytes(SOCKS5_ADDRESS_PREFIX);
//                                addrRequest.encode(addrBuff);
//
//                                ByteBuf content = Unpooled.wrappedBuffer(addrBuff, msg.content().retain());
//                                clientCtx.writeAndFlush(new DatagramPacket(content, clientSender));
//                            }
//                        });
//            }
//        }).bind(0).addListener(Sockets.logBind(0)).sync().channel());
//
//        udpChannel.attr(SSCommon.REMOTE_DEST).set(clientRecipient);
//        udpChannel.writeAndFlush(new DatagramPacket(msg.retain(), config.getServerEndpoint()));
    }
}
