package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.rx.codec.AESUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.exception.InvalidException;

@ChannelHandler.Sharable
public class CipherEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final CipherEncoder DEFAULT = new CipherEncoder();

    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.INT_LENGTH_FIELD_ENCODER, this};
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        SocketConfig conf = Sockets.getAttr(ctx.channel(), SocketConfig.ATTR_CONF);
        byte[] k;
        if ((k = conf.getCipherKey()) == null) {
            throw new InvalidException("Cipher key is empty");
        }

        if (conf.getCipher() == 1) {
            AESUtil.encrypt(msg, k, out);
        } else {
            XChaCha20Poly1305Util.encrypt(k, msg, out);
        }
    }
}
