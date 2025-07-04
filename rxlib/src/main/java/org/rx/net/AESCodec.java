package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.rx.codec.AESUtil;
import org.rx.core.Constants;

import java.util.List;

@Deprecated
@RequiredArgsConstructor
public class AESCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {
    final byte[] key;

    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{
                new LengthFieldBasedFrameDecoder(Constants.MAX_HEAP_BUF_SIZE, 0, 4, 0, 4),
                Sockets.INT_LENGTH_FIELD_ENCODER, this
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
