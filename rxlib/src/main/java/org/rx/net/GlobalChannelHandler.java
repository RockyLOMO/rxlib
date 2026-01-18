package org.rx.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.TraceHandler;

@Slf4j
@ChannelHandler.Sharable
public class GlobalChannelHandler extends ChannelDuplexHandler {
    public static final GlobalChannelHandler DEFAULT = new GlobalChannelHandler();

    //Sockets.logBind记录的比这个全
//    @Override
//    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//        super.bind(ctx, localAddress, promise);
//        promise.addListener((ChannelFutureListener) f -> {
//            Channel ch = f.channel();
//            String pn = Sockets.protocolName(ch);
//            if (!f.isSuccess()) {
//                log.error("Server[{}] {} listen on {} fail", ch.id(), pn, localAddress, f.cause());
//                return;
//            }
//            log.info("Server[{}] {} listened on {}", ch.id(), pn, localAddress);
//        });
//    }

//    @Override
//    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//        super.connect(ctx, remoteAddress, localAddress, promise);
//        promise.addListener(f -> {
//            if (!f.isSuccess()) {
//                log.error("Client[{}] connect {} fail", ctx.channel().id(), remoteAddress, f.cause());
//                return;
//            }
//            log.info("Client[{}] connected {}", ctx.channel().id(), remoteAddress);
//        });
//    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 为当前写操作的 promise 添加监听器
        promise.addListener(f -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                TraceHandler.INSTANCE.log("Channel error, write operation failed", cause);
            }
        });

        // 继续向下游传递写操作
        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        TraceHandler.INSTANCE.log("Channel error", cause);
        super.exceptionCaught(ctx, cause);
    }
}
