package org.rx.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.unix.Errors;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.ClosedChannelException;

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
                if (cause instanceof ClosedChannelException) {
//                    log.debug("Channel closed normally");
                    return;
                }
                if (cause instanceof Errors.NativeIoException) {
                    Errors.NativeIoException nativeIoException = (Errors.NativeIoException) cause;
                    if (nativeIoException.expectedErr() == Errors.ERRNO_EPIPE_NEGATIVE) {
                        // Broken pipe：正常连接关闭信号，可记录后忽略
//                        log.debug("Connection broken (EPIPE), closing channel");
                        return;
                    }
                    if (nativeIoException.expectedErr() == Errors.ERRNO_ECONNRESET_NEGATIVE) {
                        // Connection reset by peer：常见网络事件，可记录后关闭
//                        log.debug("Connection reset by peer, closing channel");
                        return;
                    }
                }
                log.error("Channel error, write operation failed", cause);
            }
        });

        // 继续向下游传递写操作
        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Channel error", cause);
        super.exceptionCaught(ctx, cause);
    }
}
