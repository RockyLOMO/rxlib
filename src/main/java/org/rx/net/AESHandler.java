package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.rx.bean.DateTime;
import org.rx.security.AESUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RequiredArgsConstructor
public class AESHandler extends MessageToMessageCodec<ByteBuf, ByteBuf> {
    public static byte[] defaultKey() {
        return String.format("℞%s", DateTime.now().toDateString()).getBytes(StandardCharsets.UTF_8);
    }

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
