package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDP 多倍发包编码器（出站 Handler）
 * <p>
 * 拦截出站 {@link DatagramPacket}，在 payload 前添加 8 字节 header（magic + seqId），
 * 然后将整个包冗余发送 {@code multiplier} 次。
 * <p>
 * 不可 {@code @Sharable}，每个 channel 持有独立的序列号发生器。
 *
 * <h3>Header 格式（8 字节）</h3>
 * <pre>
 * +-------------------+-------------------+
 * |  MAGIC (4 bytes)  |  SEQ_ID (4 bytes) |
 * +-------------------+-------------------+
 * </pre>
 *
 * <h3>发送策略</h3>
 * <ul>
 *   <li>第 1 份使用调用者的 {@link ChannelPromise}，保证写入结果正确回调</li>
 *   <li>第 2~N 份使用 {@code voidPromise}，避免多次回调</li>
 *   <li>如果 {@code intervalMicros > 0}，冗余副本通过 {@code ctx.executor().schedule()} 延迟发送，
 *       利用不同时间窗口的网络路径提高至少一份到达的概率</li>
 * </ul>
 */
@Slf4j
public class UdpRedundantEncoder extends ChannelOutboundHandlerAdapter {
    /**
     * Header magic number，用于接收端识别多倍发包协议
     */
    static final int HEADER_MAGIC = 0x52444E54; // "RDNT" in ASCII
    /**
     * Header 总长度
     */
    static final int HEADER_SIZE = 8;

    private final int multiplier;
    private final int intervalMicros;
    private final AtomicInteger seqGenerator = new AtomicInteger();

    /**
     * @param multiplier    发送倍率，[2, 5]
     * @param intervalMicros 冗余副本间隔微秒，0 = 无延迟
     */
    public UdpRedundantEncoder(int multiplier, int intervalMicros) {
        if (multiplier < 2 || multiplier > 5) {
            throw new IllegalArgumentException("multiplier must be in [2, 5], got " + multiplier);
        }
        this.multiplier = multiplier;
        this.intervalMicros = Math.max(0, intervalMicros);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        DatagramPacket original = (DatagramPacket) msg;
        ByteBuf content = original.content();
        InetSocketAddress recipient = original.recipient();
        int seqId = seqGenerator.incrementAndGet();

        // 构建带 header 的第一份包
        ByteBuf firstBuf = prependHeader(ctx, content, seqId);

        // 保存一份原始 payload 的副本用于冗余发送
        // 注意：原始 content 归属于 firstBuf 的 composite，这里需要 payload 的独立副本
        byte[] payloadBytes = null;
        if (multiplier > 1) {
            // 记录 header 之后的 payload
            int payloadLen = firstBuf.readableBytes() - HEADER_SIZE;
            payloadBytes = new byte[payloadLen];
            firstBuf.getBytes(firstBuf.readerIndex() + HEADER_SIZE, payloadBytes);
        }

        // 写入第一份（使用原始 promise）
        ctx.write(new DatagramPacket(firstBuf, recipient), promise);

        // 写入冗余副本
        if (payloadBytes != null) {
            final byte[] payload = payloadBytes;
            for (int i = 1; i < multiplier; i++) {
                final int copyIndex = i;
                if (intervalMicros > 0) {
                    // 延迟发送冗余副本
                    long delayMicros = (long) intervalMicros * copyIndex;
                    ctx.executor().schedule(() -> {
                        writeRedundantCopy(ctx, seqId, payload, recipient);
                        ctx.flush();
                    }, delayMicros, TimeUnit.MICROSECONDS);
                } else {
                    // 立即发送冗余副本
                    writeRedundantCopy(ctx, seqId, payload, recipient);
                }
            }
        }
    }

    /**
     * 在 payload 前添加 8 字节 header
     */
    private ByteBuf prependHeader(ChannelHandlerContext ctx, ByteBuf payload, int seqId) {
        ByteBuf header = ctx.alloc().directBuffer(HEADER_SIZE);
        CompositeByteBuf composite = ctx.alloc().compositeDirectBuffer(2);
        try {
            header.writeInt(HEADER_MAGIC);
            header.writeInt(seqId);
            composite.addComponents(true, header, payload.retain());
            return composite;
        } catch (Exception e) {
            header.release();
            composite.release();
            throw e;
        }
    }

    /**
     * 写入一份冗余副本（使用 voidPromise）
     */
    private void writeRedundantCopy(ChannelHandlerContext ctx, int seqId, byte[] payload, InetSocketAddress recipient) {
        ByteBuf buf = ctx.alloc().directBuffer(HEADER_SIZE + payload.length);
        try {
            buf.writeInt(HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes(payload);
            ctx.write(new DatagramPacket(buf, recipient), ctx.voidPromise());
        } catch (Exception e) {
            buf.release();
            log.warn("UDP redundant copy write failed, seq={}", seqId, e);
        }
    }
}
