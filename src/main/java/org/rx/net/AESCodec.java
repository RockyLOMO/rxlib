package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.rx.security.AESUtil;

import java.util.List;

@RequiredArgsConstructor
public class AESCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {
    final byte[] key;

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
