package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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
        int multiplier = effectiveMultiplier(recipient);
        if (multiplier <= 1) {
            super.write(ctx, msg, promise);
            return;
        }

        try {
            ByteBuf content = original.content();
            int seqId = seqGenerator.incrementAndGet();

            // 优化：直接在原始内容上添加头部，避免额外拷贝
            ByteBuf firstBuf = prependHeader(ctx, content, seqId);

            if (intervalMicros > 0) {
                // 优化：使用ByteBuf.slice避免内存拷贝
                ByteBuf payloadSlice = firstBuf.slice(firstBuf.readerIndex() + HEADER_SIZE, 
                                                 firstBuf.readableBytes() - HEADER_SIZE);
                payloadSlice.retain();
                
                ctx.write(new DatagramPacket(firstBuf, recipient), promise);
                
                // 优化：复用payloadSlice，避免重复分配
                for (int i = 1; i < multiplier; i++) {
                    final int copyIndex = i;
                    final ByteBuf payload = payloadSlice.retainedSlice(); // 独立引用
                    long delayMicros = (long) intervalMicros * copyIndex;
                    ctx.executor().schedule(() -> {
                        try {
                            writeRedundantCopyOptimized(ctx, seqId, payload, recipient);
                            ctx.flush();
                        } finally {
                            payload.release();
                        }
                    }, delayMicros, TimeUnit.MICROSECONDS);
                }
                
                // 释放slice的初始 retain 引用
                payloadSlice.release();
            } else {
                // 优化：同时发送模式，使用slice避免拷贝
                ByteBuf payloadSlice = firstBuf.slice(firstBuf.readerIndex() + HEADER_SIZE, 
                                                 firstBuf.readableBytes() - HEADER_SIZE);
                payloadSlice.retain();
                
                ctx.write(new DatagramPacket(firstBuf, recipient), promise);
                
                try {
                    for (int i = 1; i < multiplier; i++) {
                        writeRedundantCopyOptimized(ctx, seqId, payloadSlice, recipient);
                    }
                } finally {
                    payloadSlice.release();
                }
            }
        } finally {
            ReferenceCountUtil.release(original);
        }
    }

    private ByteBuf prependHeader(ChannelHandlerContext ctx, ByteBuf payload, int seqId) {
        // 优化：预计算头部大小，避免动态调整
        ByteBuf header = ctx.alloc().directBuffer(HEADER_SIZE);
        header.writeInt(HEADER_MAGIC);
        header.writeInt(seqId);
        
        // 优化：使用CompositeByteBuf实现零拷贝
        CompositeByteBuf composite = ctx.alloc().compositeDirectBuffer(2);
        try {
            // payload.retain()增加引用计数，确保payload不被释放
            composite.addComponents(true, header, payload.retain());
            return composite;
        } catch (Exception e) {
            header.release();
            composite.release();
            payload.release(); // 释放之前retain的引用
            throw e;
        }
    }

    private void writeRedundantCopyOptimized(ChannelHandlerContext ctx, int seqId, ByteBuf payload, InetSocketAddress recipient) {
        // 优化：使用CompositeByteBuf实现零拷贝
        ByteBuf header = ctx.alloc().directBuffer(HEADER_SIZE);
        try {
            header.writeInt(HEADER_MAGIC);
            header.writeInt(seqId);
            
            // 优化：使用CompositeByteBuf避免数据拷贝
            CompositeByteBuf composite = ctx.alloc().compositeDirectBuffer(2);
            try {
                // payload已经是slice，需要retain以增加引用计数
                composite.addComponents(true, header, payload.retain());
                ctx.write(new DatagramPacket(composite, recipient), ctx.voidPromise());
            } catch (Exception e) {
                composite.release();
                throw e;
            }
        } catch (Exception e) {
            header.release();
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
