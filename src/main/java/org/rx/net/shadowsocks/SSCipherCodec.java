package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.util.List;

@Slf4j
public class SSCipherCodec extends MessageToMessageCodec<Object, Object> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf;
        if (msg instanceof DatagramPacket) {
            buf = ((DatagramPacket) msg).content();
        } else if (msg instanceof ByteBuf) {
            buf = (ByteBuf) msg;
        } else {
            throw new Exception("unsupported msg type:" + msg.getClass());
        }

        ICrypto crypt = ctx.channel().attr(SSCommon.CIPHER).get();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(0, data);
        crypt.encrypt(data, buf);

        out.add(buf.retain());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf;
        if (msg instanceof DatagramPacket) {
            buf = ((DatagramPacket) msg).content();
        } else if (msg instanceof ByteBuf) {
            buf = (ByteBuf) msg;
        } else {
            throw new Exception("unsupported msg type:" + msg.getClass());
        }

        ICrypto crypt = ctx.channel().attr(SSCommon.CIPHER).get();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(0, data);
        try {
            crypt.decrypt(data, buf);
        } catch (Exception e) {
            if (e instanceof org.bouncycastle.crypto.InvalidCipherTextException) {
                log.warn("decode fail {}", e.getMessage());
                ctx.close();
                return;
            }
            throw e;
        }

        out.add(buf.retain());
    }
}
