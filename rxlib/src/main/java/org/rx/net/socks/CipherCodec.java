package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;

import java.util.List;

@Slf4j
@ChannelHandler.Sharable
public class CipherCodec extends MessageToMessageCodec<Object, Object> {
    public static final CipherCodec DEFAULT = new CipherCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf inBuf = Sockets.getMessageBuf(msg);

        ICrypto crypt = ctx.channel().attr(ShadowsocksConfig.CIPHER).get();
        ByteBuf outBuf = crypt.encrypt(inBuf);

        if (msg instanceof DatagramPacket) {
            msg = ((DatagramPacket) msg).replace(outBuf);
        } else {
            msg = outBuf;
        }
        out.add(msg);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf inBuf = Sockets.getMessageBuf(msg);

        Channel inbound = ctx.channel();
        ICrypto crypt = inbound.attr(ShadowsocksConfig.CIPHER).get();
        boolean isUdp = inbound instanceof DatagramChannel;
        try {
            ByteBuf outBuf = crypt.decrypt(inBuf);

            if (isUdp) {
                msg = ((DatagramPacket) msg).replace(outBuf);
            } else {
                msg = outBuf;
            }
            out.add(msg);
        } catch (Exception e) {
            if (e instanceof org.bouncycastle.crypto.InvalidCipherTextException) {
                log.warn("cipher decode fail", ExceptionUtils.getRootCause(e)); //可能是密码错误或协议嗅探
                if (!isUdp) {
                    inbound.close();
                }
                return;
            }
            throw e;
        }
    }
}
