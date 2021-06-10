package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.bean.DateTime;
import org.rx.security.AESUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RequiredArgsConstructor
public class AESHandler extends MessageToMessageCodec<ByteBuf, ByteBuf> {
    public static byte[] defaultKey() {
        return String.format("℞%s", DateTime.now().toDateString()).getBytes(StandardCharsets.UTF_8);
    }

    @Setter
    volatile boolean skipDecode;
    final byte[] key;

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        ByteBuf encrypt = AESUtil.encrypt(byteBuf, key);
        list.add(encrypt);
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
        if (skipDecode) {
            list.add(byteBuf);
            return;
        }

        ByteBuf decrypt = AESUtil.decrypt(byteBuf, key);
        list.add(decrypt);
    }
}
