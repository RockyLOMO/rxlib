package org.rx.net.shadowsocks.ss;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.rx.net.shadowsocks.encryption.CryptoUtil;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.util.List;

public class SSCipherCodec extends MessageToMessageCodec<Object, Object> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SSCipherCodec.class);

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

        logger.debug("encode msg size:" + buf.readableBytes());
        ICrypto _crypt = ctx.channel().attr(SSCommon.CIPHER).get();
//        Boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();
        byte[] encryptedData = CryptoUtil.encrypt(_crypt, buf);
        if (encryptedData == null || encryptedData.length == 0) {
            return;
        }

        buf.retain().clear().writeBytes(encryptedData);
        out.add(msg);
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

        logger.debug("decode msg size:" + buf.readableBytes());
        ICrypto _crypt = ctx.channel().attr(SSCommon.CIPHER).get();
        byte[] data = CryptoUtil.decrypt(_crypt, buf);
        if (data == null || data.length == 0) {
            return;
        }
        logger.debug((ctx.channel().attr(SSCommon.IS_UDP).get() ? "(UDP)" : "(TCP)") + " decode after:" + data.length);
        buf.retain().clear().writeBytes(data);
        out.add(msg);
    }
}
