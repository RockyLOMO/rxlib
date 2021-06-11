package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.rx.security.AESUtil;

import java.nio.charset.StandardCharsets;

public class AESHandler extends ChannelDuplexHandler {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        super.channelRead(ctx, msg);
        ByteBuf buf = (ByteBuf) msg;
        try {
            ByteBuf decrypt = AESUtil.decrypt(buf, AESUtil.dailyKey().getBytes(StandardCharsets.UTF_8));
            ctx.fireChannelRead(decrypt);
        } finally {
            buf.release();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        try {
            ByteBuf encrypt = AESUtil.encrypt(buf, AESUtil.dailyKey().getBytes(StandardCharsets.UTF_8));
            super.write(ctx, encrypt, promise);
        } finally {
            buf.release();
        }
    }
}
