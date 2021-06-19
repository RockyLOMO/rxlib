package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.util.List;

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
        crypt.decrypt(data, buf);

        out.add(buf.retain());
    }
}
