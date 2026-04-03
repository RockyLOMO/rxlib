package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
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
 * 支持两种模式：
 * <ul>
 *   <li><b>静态模式</b>：固定倍率，通过构造函数指定</li>
 *   <li><b>自适应模式</b>：绑定 {@link UdpRedundantStats}，定期根据丢包率动态调整倍率</li>
 * </ul>
 * <p>
 * 不可 {@code @Sharable}，每个 channel 持有独立的序列号发生器。
 *
 * <h3>Header 格式（8 字节）</h3>
 * <pre>
 * +-------------------+-------------------+
 * |  MAGIC (4 bytes)  |  SEQ_ID (4 bytes) |
 * +-------------------+-------------------+
 * </pre>
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
    /**
     * 自适应调整周期（秒）
     */
    static final int ADJUST_INTERVAL_SECONDS = 2;

    // 静态模式字段
    private final int fixedMultiplier;
    private final int intervalMicros;

    // 自适应模式字段
    private final UdpRedundantStats stats;
    private ScheduledFuture<?> adjustTimer;

    private final AtomicInteger seqGenerator = new AtomicInteger();

    /**
     * 静态模式构造
     *
     * @param multiplier     发送倍率，[2, 5]
     * @param intervalMicros 冗余副本间隔微秒，0 = 无延迟
     */
    public UdpRedundantEncoder(int multiplier, int intervalMicros) {
        if (multiplier < 2 || multiplier > 5) {
            throw new IllegalArgumentException("multiplier must be in [2, 5], got " + multiplier);
        }
        this.fixedMultiplier = multiplier;
        this.intervalMicros = Math.max(0, intervalMicros);
        this.stats = null;
    }

    /**
     * 自适应模式构造
     *
     * @param stats 共享统计对象（由 Decoder 喂入数据）
     */
    public UdpRedundantEncoder(UdpRedundantStats stats) {
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null for adaptive mode");
        }
        this.stats = stats;
        this.fixedMultiplier = 0; // 不使用
        this.intervalMicros = stats.getIntervalMicros();
    }

    /**
     * 获取当前实际倍率
     */
    private int getMultiplier() {
        if (stats != null) {
            return stats.getMultiplier();
        }
        return fixedMultiplier;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        // 自适应模式：启动定时调整
        if (stats != null) {
            adjustTimer = ctx.executor().scheduleAtFixedRate(
                    () -> stats.adjustMultiplier(),
                    ADJUST_INTERVAL_SECONDS, ADJUST_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelAdjustTimer();
        super.handlerRemoved(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        int multiplier = getMultiplier();
        if (multiplier <= 1) {
            // 自适应模式下可能降至 1，此时直接透传不加 header
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
        byte[] payloadBytes = null;
        if (multiplier > 1) {
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
                    long delayMicros = (long) intervalMicros * copyIndex;
                    ctx.executor().schedule(() -> {
                        writeRedundantCopy(ctx, seqId, payload, recipient);
                        ctx.flush();
                    }, delayMicros, TimeUnit.MICROSECONDS);
                } else {
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

    private void cancelAdjustTimer() {
        if (adjustTimer != null) {
            adjustTimer.cancel(false);
            adjustTimer = null;
        }
    }
}
