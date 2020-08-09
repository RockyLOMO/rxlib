package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@RequiredArgsConstructor
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    private final SocksProxyServer socksProxyServer;

    @Override
    protected void channelRead0(final ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) throws Exception {
        ChannelPipeline pipeline = inbound.pipeline();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        log.debug("socks5 read: {},{}:{}", msg.type(), msg.dstAddr(), msg.dstPort());

        if (!msg.type().equals(Socks5CommandType.CONNECT)) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        Sockets.bootstrap(true, inbound.channel(), null, channel -> {
            //ch.pipeline().addLast(new LoggingHandler());//in out
            if (socksProxyServer.getAuthenticator() != null) {
                socksProxyServer.getAuthenticator().upstream(ProxyChannelManageHandler.get(inbound).getUsername()).initChannel(channel);
            }
        }).connect(msg.dstAddr(), msg.dstPort()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            log.trace("socks5 connect to backend {}:{}", msg.dstAddr(), msg.dstPort());
            Channel outbound = f.channel();
            outbound.pipeline().addLast("from-upstream", new ForwardingBackendHandler(inbound));
            inbound.pipeline().addLast("to-upstream", new ForwardingFrontendHandler(outbound));
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, msg.dstAddrType()));
        });
    }
}
