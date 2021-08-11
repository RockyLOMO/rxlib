package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

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

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(SocksContext.udpSource(outbound));
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        ByteBuf outBuf;
                        if (upstream.getSocksServer() != null) {
                            outBuf = out.content().retain();
                        } else {
                            InetSocketAddress destinationEp = SocksContext.udpDestination(outbound.channel());
                            outBuf = Bytes.directBuffer();
                            outBuf.writeZero(3);
                            UdpManager.encode(outBuf, new UnresolvedEndpoint(destinationEp));
                            outBuf.writeBytes(out.content());
                        }
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
