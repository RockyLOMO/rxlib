package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;

import javax.crypto.BadPaddingException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@ChannelHandler.Sharable
public class CipherCodec extends MessageToMessageCodec<Object, Object> {
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong LAST_WARNING = new AtomicLong();
    public static final CipherCodec DEFAULT = new CipherCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf inBuf = Sockets.getMessageBuf(msg);

        // TCP empty writes are flush barriers, not Shadowsocks records.
        if (!(msg instanceof DatagramPacket) && !inBuf.isReadable()) {
            out.add(ReferenceCountUtil.retain(msg));
            return;
        }

        ICrypto crypt = ctx.channel().attr(ShadowsocksConfig.CIPHER).get();
        if (crypt == null) {
            out.add(ReferenceCountUtil.retain(msg));
            return;
        }

        ByteBuf outBuf = crypt.encrypt(inBuf);
        boolean transferred = false;
        try {
            if (!outBuf.isReadable()) {
                return;
            }

            if (msg instanceof DatagramPacket) {
                msg = ((DatagramPacket) msg).replace(outBuf);
            } else {
                msg = outBuf;
            }
            out.add(msg);
            transferred = true;
        } finally {
            if (!transferred) {
                Bytes.release(outBuf);
            }
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf inBuf = Sockets.getMessageBuf(msg);

        Channel inbound = ctx.channel();
        ICrypto crypt = inbound.attr(ShadowsocksConfig.CIPHER).get();
        if (crypt == null) {
            out.add(ReferenceCountUtil.retain(msg));
            return;
        }

        boolean isUdp = inbound instanceof DatagramChannel;
        try {
            ByteBuf outBuf = crypt.decrypt(inBuf);
            boolean transferred = false;
            try {
                if (!outBuf.isReadable()) {
                    return;
                }

                if (isUdp) {
                    msg = ((DatagramPacket) msg).replace(outBuf);
                } else {
                    msg = outBuf;
                }
                out.add(msg);
                transferred = true;
            } finally {
                if (!transferred) {
                    Bytes.release(outBuf);
                }
            }
        } catch (Exception e) {
            if (isAuthenticationFailure(e)) {
                recordRejection();
                if (!isUdp) {
                    inbound.close();
                }
                return;
            }
            throw e;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closeCrypto(ctx.channel());
        super.handlerRemoved(ctx);
    }

    private static boolean isAuthenticationFailure(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof BadPaddingException) {
                return true;
            }
        }
        return false;
    }

    private static void closeCrypto(Channel channel) throws Exception {
        ICrypto crypt = channel.attr(ShadowsocksConfig.CIPHER).getAndSet(null);
        if (crypt instanceof AutoCloseable) {
            ((AutoCloseable) crypt).close();
        }
    }

    private static void recordRejection() {
        REJECTED.incrementAndGet();
        long now = System.nanoTime(), last = LAST_WARNING.get();
        if ((last == 0 || now - last >= TimeUnit.SECONDS.toNanos(30)) && LAST_WARNING.compareAndSet(last, now)) {
            long count = REJECTED.getAndSet(0);
            DiagnosticMetrics.record("ss.aead.rejected", count);
            log.warn("Rejected {} invalid Shadowsocks AEAD records since previous warning", count);
        }
    }
}
