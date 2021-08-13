package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.GenericInboundHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends GenericInboundHandler<ByteBuf> {
    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext inbound, ByteBuf inBuf) throws Exception {
        InetSocketAddress clientSender = inbound.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        InetSocketAddress clientRecipient = inbound.channel().attr(SSCommon.REMOTE_DEST).get();
        ShadowsocksServer server = SocksContext.ssServer(inbound.channel());

        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(clientSender, k -> {
            Upstream upstream = server.udpRouter.invoke(new UnresolvedEndpoint(clientRecipient));
            Channel channel = Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                upstream.initChannel(outbound);

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getIdleTimeout()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(clientSender);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new GenericInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
                        ByteBuf outBuf = out.content();
                        log.info("RECV\n{}", ByteBufUtil.prettyHexDump(outBuf));
                        if (upstream.getSocksServer() != null) {
                            UnresolvedEndpoint dstEp = UdpManager.socks5Decode(outBuf);
                            log.info("RECV DECODE {}[{}] => {} {}", out.sender(), dstEp, clientSender, outBuf);
//                            inbound.channel().attr(SSCommon.REMOTE_DEST).set(out.sender());
                        }

                        inbound.channel().attr(SSCommon.REMOTE_SRC).set(out.sender());
                        inbound.channel().writeAndFlush(outBuf);
                    }
                });
            }).bind(0).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
            SocksContext.initPendingQueue(channel, clientSender, upstream.getDestination());
            return new UdpManager.UdpChannelUpstream(channel, upstream);
        });

        log.info("SEND\n{}", ByteBufUtil.prettyHexDump(inBuf));
        UnresolvedEndpoint dstEp = outCtx.getUpstream().getDestination();
        AuthenticEndpoint svrEp = outCtx.getUpstream().getSocksServer();
        if (svrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            dstEp = new UnresolvedEndpoint(svrEp.getEndpoint());
        }
        UdpManager.pendOrWritePacket(outCtx.getChannel(), new DatagramPacket(inBuf, dstEp.socketAddress()));
    }
}
