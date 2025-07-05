package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.rx.codec.AESUtil;
import org.rx.exception.InvalidException;

import java.util.List;

public class AESDecoder extends ByteToMessageDecoder {
    public ChannelHandler[] channelHandlers() {
        return new ChannelHandler[]{Sockets.intLengthFieldDecoder(), this};
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        byte[] k = ctx.channel().attr(SocketConfig.ATTR_AES_KEY).get();
        if (k == null) {
            throw new InvalidException("AES key is empty");
        }

        ByteBuf decrypt = AESUtil.decrypt(in, k);
        out.add(decrypt);
    }
}
