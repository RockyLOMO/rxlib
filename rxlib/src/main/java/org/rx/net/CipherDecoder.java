package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.rx.codec.AESUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;

import java.util.List;

public class CipherDecoder extends ByteToMessageDecoder {
    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.intLengthFieldDecoder(), this};
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        SocketConfig conf = Sockets.getAttr(ctx.channel(), SocketConfig.ATTR_CONF);
        byte[] k;
        if ((k = conf.getCipherKey()) == null) {
            throw new InvalidException("Cipher key is empty");
        }

        ByteBuf decrypt = null;
        try {
            if (conf.getCipher() == 1) {
                decrypt = ctx.alloc().ioBuffer(in.readableBytes());
                AESUtil.decrypt(in, k, decrypt);
            } else {
                int readable = in.readableBytes();
                decrypt = ctx.alloc().ioBuffer(Math.max(0, readable - 40));
                XChaCha20Poly1305Util.decrypt(k, in, decrypt);
                in.skipBytes(readable);
            }
            out.add(decrypt);
        } catch (Throwable e) {
            Bytes.release(decrypt);
            throw e;
        }
    }
}
