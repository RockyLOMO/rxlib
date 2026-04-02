package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FEC 编码器 (Outbound Handler)
 * <p>
 * 拦截出站 DatagramPacket，每 K 个数据包生成 1 个 XOR 奇偶校验包。
 * 用于游戏加速场景的前向纠错。
 */
@Slf4j
public class FecEncoder extends ChannelOutboundHandlerAdapter {
    private final int groupSize;
    private final int flushTimeoutMs;
    private final AtomicInteger seqCounter = new AtomicInteger();
    private final AtomicInteger groupCounter = new AtomicInteger();

    // 当前组状态
    private byte[][] groupBuffers;
    private int groupIdx;
    private int currentGroupId;
    private int maxPayloadLen;
    private InetSocketAddress lastRemote;
    private ScheduledFuture<?> flushTimer;

    public FecEncoder(FecConfig config) {
        this.groupSize = config.getGroupSize();
        this.flushTimeoutMs = config.getFlushTimeoutMs();
        resetGroup();
    }

    private void resetGroup() {
        this.groupBuffers = new byte[groupSize][];
        this.groupIdx = 0;
        this.currentGroupId = groupCounter.incrementAndGet();
        this.maxPayloadLen = 0;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf content = packet.content();
        InetSocketAddress remote = packet.recipient();
        lastRemote = remote;

        // 提取 payload
        byte[] payload = new byte[content.readableBytes()];
        content.readBytes(payload);
        content.release();

        // 写入数据包
        FecPacket fecPkt = new FecPacket(seqCounter.incrementAndGet(), currentGroupId, (byte) groupIdx, false, payload);
        writePacket(ctx, fecPkt, remote, promise);

        // 缓存到组
        groupBuffers[groupIdx] = payload;
        if (payload.length > maxPayloadLen) {
            maxPayloadLen = payload.length;
        }
        groupIdx++;

        // 组满了，生成奇偶校验包
        if (groupIdx >= groupSize) {
            cancelFlushTimer();
            emitParity(ctx, remote);
            resetGroup();
        } else {
            scheduleFlush(ctx);
        }
    }

    private void writePacket(ChannelHandlerContext ctx, FecPacket fecPkt, InetSocketAddress remote, ChannelPromise promise) {
        ByteBuf buf = ctx.alloc().buffer(fecPkt.encodedLength());
        fecPkt.encode(buf);
        ctx.write(new DatagramPacket(buf, remote), promise);
    }

    private void emitParity(ChannelHandlerContext ctx, InetSocketAddress remote) {
        byte[] parity = computeXorParity(groupBuffers, groupIdx, maxPayloadLen);
        FecPacket parityPkt = new FecPacket(seqCounter.incrementAndGet(), currentGroupId, (byte) groupIdx, true, parity);

        ByteBuf buf = ctx.alloc().buffer(parityPkt.encodedLength());
        parityPkt.encode(buf);
        ctx.write(new DatagramPacket(buf, remote), ctx.voidPromise());
        ctx.flush();
        log.debug("FEC parity emitted, groupId={}, size={}", currentGroupId, groupIdx);
    }

    /**
     * 计算 XOR 奇偶校验
     */
    static byte[] computeXorParity(byte[][] buffers, int count, int maxLen) {
        byte[] parity = new byte[maxLen];
        for (int i = 0; i < count; i++) {
            byte[] b = buffers[i];
            if (b == null) {
                continue;
            }
            for (int j = 0; j < b.length; j++) {
                parity[j] ^= b[j];
            }
        }
        return parity;
    }

    private void scheduleFlush(ChannelHandlerContext ctx) {
        cancelFlushTimer();
        if (flushTimeoutMs <= 0) {
            return;
        }
        flushTimer = ctx.executor().schedule(() -> {
            if (groupIdx > 0) {
                log.debug("FEC flush timeout, emitting partial parity groupId={}, count={}", currentGroupId, groupIdx);
                if (lastRemote != null) {
                    emitParity(ctx, lastRemote);
                }
                resetGroup();
            }
        }, flushTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelFlushTimer() {
        if (flushTimer != null) {
            flushTimer.cancel(false);
            flushTimer = null;
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        cancelFlushTimer();
        super.close(ctx, promise);
    }
}
