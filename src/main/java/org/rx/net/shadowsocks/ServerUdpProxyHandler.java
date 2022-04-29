package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.RouteEventArgs;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext inbound, ByteBuf inBuf) throws Exception {
        InetSocketAddress srcEp = inbound.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.channel().attr(SSCommon.REMOTE_DEST).get());
        ShadowsocksServer server = SocksContext.ssServer(inbound.channel());

        Channel outbound = UdpManager.openChannel(srcEp, k -> {
            RouteEventArgs e = new RouteEventArgs(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getValue();
            return SocksContext.initOutbound(Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                upstream.initChannel(ob);

                ob.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getIdleTimeout()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(srcEp);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) {
                        ByteBuf outBuf = out.content();
                        if (upstream.getSocksServer() != null) {
                            UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                            if (!dstEp.equals(tmp)) {
                                log.error("UDP SOCKS ERROR {} != {}", dstEp, tmp);
                            }
                        }

                        inbound.attr(SSCommon.REMOTE_SRC).set(out.sender());
                        inbound.writeAndFlush(outBuf.retain());
                        log.info("UDP IN {}[{}] => {}", out.sender(), dstEp, srcEp);
                    }
                });
            }).bind(0)
                    .addListener(UdpManager.FLUSH_PENDING_QUEUE).channel(), srcEp, dstEp, upstream)
//                    .syncUninterruptibly().channel(), clientSender, upstream.getDestination(), upstream, false)
                    ;
        });

        Upstream upstream = SocksContext.upstream(outbound);
        UnresolvedEndpoint upDstEp = upstream.getDestination();
        AuthenticEndpoint svrEp = upstream.getSocksServer();
        if (svrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            upDstEp = new UnresolvedEndpoint(svrEp.getEndpoint());
        } else {
            inBuf.retain();
        }
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(inBuf, upDstEp.socketAddress()));
        log.info("UDP OUT {} => {}[{}]", srcEp, upDstEp, dstEp);
    }
}
