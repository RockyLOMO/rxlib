package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.rx.bean.Tuple;
import org.rx.codec.AESUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.exception.InvalidException;

import java.util.List;

public class CipherDecoder extends ByteToMessageDecoder {
    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.intLengthFieldDecoder(), this};
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Tuple<Short, byte[]> t = ctx.channel().attr(SocketConfig.ATTR_CIPHER_KEY).get();
        byte[] k;
        if (t == null || (k = t.right) == null) {
            throw new InvalidException("Cipher key is empty");
        }

        ByteBuf decrypt;
        if (t.left == 1) {
            decrypt = AESUtil.decrypt(in, k);
        } else {
            byte[] msgBytes = new byte[in.readableBytes()];
            in.readBytes(msgBytes);
            decrypt = Unpooled.wrappedBuffer(XChaCha20Poly1305Util.decrypt(k, msgBytes));
        }
        out.add(decrypt);
    }
}
