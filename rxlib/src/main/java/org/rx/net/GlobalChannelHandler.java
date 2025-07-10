package org.rx.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class GlobalChannelHandler extends ChannelDuplexHandler {
    public static final GlobalChannelHandler DEFAULT = new GlobalChannelHandler();

    //Sockets.logBind记录的比这个全
//    @Override
//    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//        super.bind(ctx, localAddress, promise);
//        promise.addListener((ChannelFutureListener) f -> {
//            System.out.println(111111);
//            Channel ch = f.channel();
//            String pn = Sockets.protocolName(ch);
//            if (!f.isSuccess()) {
//                log.error("Server[{}] {} listen on {} fail", ch.id(), pn, localAddress, f.cause());
//                return;
//            }
//            log.info("Server[{}] {} listened on {}", ch.id(), pn, localAddress);
//        });
//    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        super.connect(ctx, remoteAddress, localAddress, promise);
        promise.addListener(f -> {
            if (!f.isSuccess()) {
                log.error("Client[{}] connect {} fail", ctx.channel().id(), remoteAddress, f.cause());
                return;
            }
            log.info("Client[{}] connected {}", ctx.channel().id(), remoteAddress);
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("exceptionCaught", cause);
//        super.exceptionCaught(ctx, cause);
    }
}
