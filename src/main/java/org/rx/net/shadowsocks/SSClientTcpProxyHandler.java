package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.socks.SocksAddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.CryptoFactory;
import org.rx.net.socks.ForwardingBackendHandler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class SSClientTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    final ShadowsocksConfig config;
    final ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
    Channel outbound;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        Channel inbound = ctx.channel();
        if (outbound == null) {
            Socks5CommandRequest remoteRequest = inbound.attr(SSCommon.REMOTE_SOCKS5_DEST).get();
            //write addr
            SSAddressRequest addrRequest = new SSAddressRequest(SocksAddressType.valueOf(remoteRequest.dstAddrType().byteValue()), remoteRequest.dstAddr(), remoteRequest.dstPort());
            ByteBuf addrBuf = Unpooled.buffer(128);
            addrRequest.encode(addrBuf);
            pendingPackages.add(addrBuf);

            outbound = Sockets.bootstrap(inbound.eventLoop(), config, ch -> {
                ch.attr(SSCommon.IS_UDP).set(false);
                ch.attr(SSCommon.CIPHER).set(CryptoFactory.get(config.getMethod(), config.getPassword()));

                ch.pipeline().addLast(new IdleStateHandler(0, 0, SSCommon.TCP_PROXY_IDLE_TIME, TimeUnit.SECONDS) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        Sockets.closeOnFlushed(outbound);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SSCipherCodec(), new SSProtocolCodec(true), new ForwardingBackendHandler(ctx, pendingPackages));
            }).connect(config.getServerEndpoint()).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("SsClient connect to backend {} fail", config.getServerEndpoint(), f.cause());
                    Sockets.closeOnFlushed(inbound);
                    return;
                }
                log.info("SsClient connect to backend {}", config.getServerEndpoint());
            }).channel();
        }

        if (!outbound.isActive()) {
            log.debug("xx add pending packages");
            pendingPackages.add(msg.retain());
            return;
        }

        log.debug("{} forwarded to {} -> {}", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg.retain()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Sockets.closeOnFlushed(outbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn("{} forwarded to {} -> {} thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
