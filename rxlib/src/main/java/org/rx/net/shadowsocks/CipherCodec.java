package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.util.List;

@Slf4j
@ChannelHandler.Sharable
public class CipherCodec extends MessageToMessageCodec<Object, Object> {
    public static final CipherCodec DEFAULT = new CipherCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        ICrypto crypt = ctx.channel().attr(SSCommon.CIPHER).get();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(0, data);
        crypt.encrypt(data, buf);

        out.add(buf.retain());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        ICrypto crypt = ctx.channel().attr(SSCommon.CIPHER).get();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(0, data);
        try {
            crypt.decrypt(data, buf);
        } catch (Exception e) {
            if (e instanceof org.bouncycastle.crypto.InvalidCipherTextException) {
                log.warn("decode fail {}", e.getMessage()); //可能是密码错误
                ctx.close();
                return;
            }
            throw e;
        }

        out.add(buf.retain());
    }
}
