package org.rx.diagnostic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;

import java.net.SocketAddress;

@ChannelHandler.Sharable
public final class DiagnosticNetIoHandler extends ChannelDuplexHandler {
    public static final DiagnosticNetIoHandler INSTANCE = new DiagnosticNetIoHandler();

    private DiagnosticNetIoHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        long bytes = readableBytes(msg);
        if (bytes > 0L) {
            DiagnosticNetIo.recordInbound(endpoint(ctx), bytes);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        long bytes = readableBytes(msg);
        if (bytes > 0L) {
            DiagnosticNetIo.recordOutbound(endpoint(ctx), bytes);
        }
        super.write(ctx, msg, promise);
    }

    private static long readableBytes(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        return 0L;
    }

    private static String endpoint(ChannelHandlerContext ctx) {
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote != null) {
            return remote.toString();
        }
        SocketAddress local = ctx.channel().localAddress();
        return local == null ? "unknown" : local.toString();
    }
}
