package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.rx.codec.AESUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.exception.InvalidException;
import org.rx.net.socks.SocksContext;

@ChannelHandler.Sharable
public class CipherEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final CipherEncoder DEFAULT = new CipherEncoder();

    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.INT_LENGTH_FIELD_ENCODER, this};
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        SocketConfig conf = SocksContext.getAttr(ctx.channel(), SocksContext.SOCKS_CONF);
        byte[] k;
        if ((k = conf.getCipherKey()) == null) {
            throw new InvalidException("Cipher key is empty");
        }

        ByteBuf encrypt;
        if (conf.getCipher() == 1) {
            encrypt = AESUtil.encrypt(msg, k);
        } else {
            byte[] msgBytes = new byte[msg.readableBytes()];
            msg.readBytes(msgBytes);
            encrypt = Unpooled.wrappedBuffer(XChaCha20Poly1305Util.encrypt(k, msgBytes));
        }
        out.writeBytes(encrypt);
    }
}
