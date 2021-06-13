package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.rx.bean.RxConfig;
import org.rx.security.AESUtil;

import java.util.List;

@RequiredArgsConstructor
public class AESCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {
    final byte[] key;

    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{
                new LengthFieldBasedFrameDecoder(RxConfig.MAX_HEAP_BUF_SIZE, 0, 4, 0, 4),
                new LengthFieldPrepender(4), this
        };
    }

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        ByteBuf encrypt = AESUtil.encrypt(byteBuf, key);
        list.add(encrypt);
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        ByteBuf decrypt = AESUtil.decrypt(byteBuf, key);
        list.add(decrypt);
    }
}
