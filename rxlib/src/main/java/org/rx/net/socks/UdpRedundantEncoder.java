package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
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
 * 支持：
 * <ul>
 *   <li><b>静态模式</b>：固定倍率，通过构造函数指定</li>
 *   <li><b>自适应模式</b>：绑定 {@link UdpRedundantStats}，定期根据丢包率动态调整倍率</li>
 *   <li><b>分目的地倍率</b>：可选 {@link UdpRedundantMultiplierResolver}，命中规则时覆盖上述倍率</li>
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

    private final UdpRedundantMultiplierResolver perDestination;

    private final AtomicInteger seqGenerator = new AtomicInteger();

    /**
     * 静态模式构造
     *
     * @param multiplier     发送倍率，[1, 5]。1 = 不启用冗余（透传）
     * @param intervalMicros 冗余副本间隔微秒，0 = 无延迟
     */
    public UdpRedundantEncoder(int multiplier, int intervalMicros) {
        this(multiplier, intervalMicros, null);
    }

    /**
     * 静态模式 + 分目的地倍率
     *
     * @param perDestination 可为 null；未命中规则时使用 {@code multiplier}
     */
    public UdpRedundantEncoder(int multiplier, int intervalMicros, UdpRedundantMultiplierResolver perDestination) {
        if (multiplier < 1 || multiplier > 5) {
            throw new IllegalArgumentException("multiplier must be in [1, 5], got " + multiplier);
        }
        this.fixedMultiplier = multiplier;
        this.intervalMicros = Math.max(0, intervalMicros);
        this.stats = null;
        this.perDestination = perDestination;
    }

    /**
     * 自适应模式构造
     *
     * @param stats 共享统计对象（由 Decoder 喂入数据）
     */
    public UdpRedundantEncoder(UdpRedundantStats stats) {
        this(stats, null);
    }

    /**
     * 自适应模式 + 分目的地倍率（命中规则时以规则倍率为准）
     */
    public UdpRedundantEncoder(UdpRedundantStats stats, UdpRedundantMultiplierResolver perDestination) {
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null for adaptive mode");
        }
        this.stats = stats;
        this.fixedMultiplier = 0;
        this.intervalMicros = stats.getIntervalMicros();
        this.perDestination = perDestination;
    }

    private int effectiveMultiplier(InetSocketAddress recipient) {
        int ruleMult = UdpRedundantMultiplierResolver.NO_MATCH;
        if (perDestination != null && recipient != null) {
            ruleMult = perDestination.resolve(recipient);
        }
        if (ruleMult >= 1 && ruleMult <= 5) {
            return ruleMult;
        }
        if (stats != null) {
            return stats.getMultiplier();
        }
        return fixedMultiplier;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
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

        DatagramPacket original = (DatagramPacket) msg;
        InetSocketAddress recipient = original.recipient();
        if (!UdpRelayAttributes.shouldEncode(ctx.channel(), recipient)) {
            super.write(ctx, msg, promise);
            return;
        }
        int multiplier = effectiveMultiplier(recipient);
        if (multiplier <= 1) {
            super.write(ctx, msg, promise);
            return;
        }

        try {
            ByteBuf content = original.content();
            int seqId = seqGenerator.incrementAndGet();

            // 线上 epoll 已确认 composite 可能触发 sendmmsg/sendToAddress EINVAL。
            // 这里使用单段 direct packet + retainedDuplicate，兼顾 native 兼容性与零拷贝副本。
            ByteBuf packetBuf = prependHeader(ctx, content, seqId);
            try {
                if (intervalMicros > 0) {
                    writeFirstCopy(ctx, packetBuf.retainedDuplicate(), recipient, promise);
                    ctx.flush();

                    for (int i = 1; i < multiplier; i++) {
                        final int copyIndex = i;
                        final ByteBuf copy = packetBuf.retainedDuplicate();
                        long delayMicros = (long) intervalMicros * copyIndex;
                        try {
                            ctx.executor().schedule(() -> {
                                writeRedundantCopy(ctx, seqId, copy, recipient);
                                ctx.flush();
                            }, delayMicros, TimeUnit.MICROSECONDS);
                        } catch (Exception e) {
                            copy.release();
                            throw e;
                        }
                    }
                } else {
                    writeFirstCopy(ctx, packetBuf.retainedDuplicate(), recipient, promise);

                    for (int i = 1; i < multiplier; i++) {
                        writeRedundantCopy(ctx, seqId, packetBuf.retainedDuplicate(), recipient);
                    }
                    ctx.flush();
                }
            } finally {
                packetBuf.release();
            }
        } finally {
            ReferenceCountUtil.release(original);
        }
    }

    private ByteBuf prependHeader(ChannelHandlerContext ctx, ByteBuf payload, int seqId) {
        int len = payload.readableBytes();
        int ri = payload.readerIndex();
        ByteBuf buf = ctx.alloc().directBuffer(HEADER_SIZE + len);
        try {
            buf.writeInt(HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes(payload, ri, len);
            return buf;
        } catch (Exception e) {
            buf.release();
            throw e;
        }
    }

    private void writeFirstCopy(ChannelHandlerContext ctx, ByteBuf packetBuf,
                                InetSocketAddress recipient, ChannelPromise promise) throws Exception {
        boolean written = false;
        try {
            ctx.write(new DatagramPacket(packetBuf, recipient), promise);
            written = true;
        } catch (Exception e) {
            if (!written) {
                packetBuf.release();
            }
            throw e;
        }
    }

    private void writeRedundantCopy(ChannelHandlerContext ctx, int seqId, ByteBuf packetBuf, InetSocketAddress recipient) {
        boolean written = false;
        try {
            ctx.write(new DatagramPacket(packetBuf, recipient), ctx.voidPromise());
            written = true;
        } catch (Exception e) {
            if (!written) {
                packetBuf.release();
            }
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
