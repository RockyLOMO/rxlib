package org.rx.net;

import io.netty.channel.*;
import io.netty.channel.unix.Errors;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;

@Slf4j
@ChannelHandler.Sharable
public class GlobalChannelHandler extends ChannelDuplexHandler {
    public static final GlobalChannelHandler DEFAULT = new GlobalChannelHandler();

    static final AttributeKey<InetSocketAddress> ATTR_BIND_ADDR = AttributeKey.valueOf("ATTR_BIND_ADDR");

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        super.bind(ctx, localAddress, promise);
        ctx.channel().attr(ATTR_BIND_ADDR).set((InetSocketAddress) localAddress);
        promise.addListener((ChannelFutureListener) f -> {
            Channel ch = f.channel();
            String pn = Sockets.protocolName(ch);
            InetSocketAddress bindAddr = ch.attr(ATTR_BIND_ADDR).get();
            ch.attr(ATTR_BIND_ADDR).set(null);
            if (!f.isSuccess()) {
                log.error("Channel[{}] {} listen on {} fail", ch.id(), pn, bindAddr, f.cause());
                return;
            }
            bindAddr = (InetSocketAddress) ch.localAddress();
            log.info("Channel[{}] {} listened on {}", ch.id(), pn, bindAddr);
        });
    }

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
        super.write(ctx, msg, promise);
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
                log.error("Channel write operation failed", cause);
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Channel error", cause);
//        super.exceptionCaught(ctx, cause);
    }
}
