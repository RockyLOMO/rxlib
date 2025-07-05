package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.rx.codec.AESUtil;
import org.rx.exception.InvalidException;

@ChannelHandler.Sharable
public class AESEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final AESEncoder DEFAULT = new AESEncoder();

    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.INT_LENGTH_FIELD_ENCODER, this};
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        byte[] k = ctx.channel().attr(SocketConfig.ATTR_AES_KEY).get();
        if (k == null) {
            throw new InvalidException("AES key is empty");
        }

        ByteBuf encrypt = AESUtil.encrypt(msg, k);
        out.writeBytes(encrypt);
    }
}
