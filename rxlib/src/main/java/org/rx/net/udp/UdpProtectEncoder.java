package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDP Protect 出站编码器。不可 Sharable，每个 channel 持有独立 peer/FEC 状态。
 */
@Slf4j
public class UdpProtectEncoder extends ChannelDuplexHandler {
    private static final int CLEANUP_INTERVAL = 1024;

    private final UdpProtectConfig config;
    private final UdpProtectStats stats;
    private final LinkedHashMap<PeerKey, PeerState> peers = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicInteger pendingDelayedCopies = new AtomicInteger();
    private int cleanupCounter;
    private volatile boolean closed;

    public UdpProtectEncoder(UdpProtectConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.stats = config.stats();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        InetSocketAddress recipient = packet.recipient();
        if (!config.isEnabled()
                || !UdpProtectAttributes.shouldProtect(ctx.channel(), recipient, config.isProtectAll())
                || !shouldProtectPayload()) {
            super.write(ctx, msg, promise);
            return;
        }

        ByteBuf content = packet.content();
        int payloadLen = content.readableBytes();
        if (payloadLen > config.getMaxProtectedPayload()) {
            stats.recordTooLarge();
            if (!config.isDropTooLarge()) {
                super.write(ctx, msg, promise);
                return;
            }
            ReferenceCountUtil.release(packet);
            promise.setFailure(new TooLongFrameException("udp protect payload too large: " + payloadLen));
            return;
        }

        boolean releaseOriginal = true;
        try {
            if (++cleanupCounter >= CLEANUP_INTERVAL) {
                cleanupCounter = 0;
                cleanupIdlePeers(System.nanoTime());
            }
            int flowId = config.getFlowIdResolver().resolve(ctx.channel(), packet);
            PeerState state = peerState(recipient, flowId);
            if (state == null) {
                if (config.isDropOnLimit()) {
                    promise.setSuccess();
                } else {
                    releaseOriginal = false;
                    super.write(ctx, msg, promise);
                }
                return;
            }

            if (config.isFecEnabled() && config.getFecParityShards() == 1) {
                writeFecData(ctx, packet, state, flowId, promise);
            } else {
                writeDataOnly(ctx, packet, state, flowId, promise);
            }
        } finally {
            if (releaseOriginal) {
                ReferenceCountUtil.release(packet);
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        releaseAllPeers();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        releaseAllPeers();
        super.channelInactive(ctx);
    }

    private boolean shouldProtectPayload() {
        return (config.isFecEnabled() && config.getFecParityShards() == 1)
                || (config.isRedundantEnabled() && effectiveDataMultiplier() > 1);
    }

    private void writeFecData(ChannelHandlerContext ctx, DatagramPacket packet, PeerState state,
                              int flowId, ChannelPromise promise) {
        ByteBuf content = packet.content();
        int payloadLen = content.readableBytes();
        int payloadIndex = content.readerIndex();

        FecEncodeGroup group = state.group;
        if (group == null) {
            group = state.newGroup(config.getFecDataShards());
        }
        int shardIdx = group.count();
        int seq = state.nextSeq++;
        ByteBuf protectedBuf = encodePacket(ctx, UdpProtectHeader.FLAG_DATA, UdpProtectHeader.CODEC_XOR,
                group.capacity(), 1, shardIdx, flowId, seq, group.groupId,
                payloadLen, content, payloadIndex, payloadLen);
        writeProtected(ctx, protectedBuf, packet.recipient(), effectiveDataMultiplier(), promise);
        stats.recordProtectedData();

        ByteBuf block = ctx.alloc().directBuffer(2 + payloadLen);
        try {
            block.writeShort(payloadLen);
            block.writeBytes(content, payloadIndex, payloadLen);
            group.add(block);
            block = null;
        } finally {
            if (block != null) {
                block.release();
            }
        }

        if (group.isFull()) {
            state.cancelFlush();
            emitParity(ctx, state, flowId);
            state.clearGroup();
        } else {
            scheduleFlush(ctx, state, flowId);
        }
    }

    private void writeDataOnly(ChannelHandlerContext ctx, DatagramPacket packet, PeerState state,
                               int flowId, ChannelPromise promise) {
        ByteBuf content = packet.content();
        int payloadLen = content.readableBytes();
        int seq = state.nextSeq++;
        ByteBuf protectedBuf = encodePacket(ctx, UdpProtectHeader.FLAG_DATA, UdpProtectHeader.CODEC_NONE,
                1, 0, 0, flowId, seq, 0, payloadLen, content, content.readerIndex(), payloadLen);
        writeProtected(ctx, protectedBuf, packet.recipient(), effectiveDataMultiplier(), promise);
        stats.recordProtectedData();
    }

    private void emitParity(ChannelHandlerContext ctx, PeerState state, int flowId) {
        FecEncodeGroup group = state.group;
        if (group == null || group.count() == 0) {
            return;
        }

        ByteBuf parity = group.buildParity(ctx.alloc());
        try {
            int parityLen = parity.readableBytes();
            int seq = state.nextSeq++;
            ByteBuf protectedBuf = encodePacket(ctx, UdpProtectHeader.FLAG_PARITY, UdpProtectHeader.CODEC_XOR,
                    group.count(), 1, group.count(), flowId, seq, group.groupId,
                    parityLen, parity, parity.readerIndex(), parityLen);
            writeProtected(ctx, protectedBuf, state.recipient, effectiveParityMultiplier(), ctx.voidPromise());
            stats.recordParity();
        } finally {
            parity.release();
        }
    }

    private ByteBuf encodePacket(ChannelHandlerContext ctx, int flags, int codec, int shardK, int shardP,
                                 int shardIdx, int flowId, int seq, int groupId, int payloadLen,
                                 ByteBuf payload, int payloadIndex, int payloadBytes) {
        ByteBuf out = ctx.alloc().directBuffer(UdpProtectHeader.HEADER_SIZE + payloadBytes);
        try {
            UdpProtectHeader.write(out, flags, codec, shardK, shardP, shardIdx, flowId, seq, groupId, payloadLen);
            out.writeBytes(payload, payloadIndex, payloadBytes);
            return out;
        } catch (Throwable e) {
            out.release();
            throw e;
        }
    }

    private void writeProtected(ChannelHandlerContext ctx, ByteBuf packetBuf, InetSocketAddress recipient,
                                int multiplier, ChannelPromise promise) {
        if (multiplier <= 1) {
            boolean written = false;
            try {
                ctx.write(new DatagramPacket(packetBuf, recipient), promise);
                written = true;
            } finally {
                if (!written) {
                    packetBuf.release();
                }
            }
            return;
        }

        try {
            writeCopy(ctx, packetBuf.retainedDuplicate(), recipient, promise);
            if (config.getRedundantIntervalMicros() <= 0) {
                for (int i = 1; i < multiplier; i++) {
                    writeCopy(ctx, packetBuf.retainedDuplicate(), recipient, ctx.voidPromise());
                    stats.recordRedundantCopy();
                }
                return;
            }

            for (int i = 1; i < multiplier; i++) {
                scheduleCopy(ctx, packetBuf.retainedDuplicate(), recipient, i);
            }
        } finally {
            packetBuf.release();
        }
    }

    private void writeCopy(ChannelHandlerContext ctx, ByteBuf copy, InetSocketAddress recipient, ChannelPromise promise) {
        boolean written = false;
        try {
            ctx.write(new DatagramPacket(copy, recipient), promise);
            written = true;
        } finally {
            if (!written) {
                copy.release();
            }
        }
    }

    private void scheduleCopy(ChannelHandlerContext ctx, ByteBuf copy, InetSocketAddress recipient, int copyIndex) {
        if (pendingDelayedCopies.incrementAndGet() > config.getMaxPendingDelayedCopies()) {
            pendingDelayedCopies.decrementAndGet();
            writeCopy(ctx, copy, recipient, ctx.voidPromise());
            stats.recordRedundantCopy();
            return;
        }
        try {
            long delayMicros = (long) config.getRedundantIntervalMicros() * copyIndex;
            ctx.executor().schedule(() -> {
                try {
                    if (closed || !ctx.channel().isActive()) {
                        copy.release();
                        return;
                    }
                    writeCopy(ctx, copy, recipient, ctx.voidPromise());
                    stats.recordRedundantCopy();
                    ctx.flush();
                } finally {
                    pendingDelayedCopies.decrementAndGet();
                }
            }, delayMicros, TimeUnit.MICROSECONDS);
        } catch (Throwable e) {
            pendingDelayedCopies.decrementAndGet();
            copy.release();
            throw e;
        }
    }

    private void scheduleFlush(ChannelHandlerContext ctx, PeerState state, int flowId) {
        if (config.getFecFlushTimeoutMs() <= 0) {
            return;
        }
        state.cancelFlush();
        state.flushFuture = ctx.executor().schedule(() -> {
            if (closed || state.group == null || state.group.count() == 0) {
                return;
            }
            emitParity(ctx, state, flowId);
            state.clearGroup();
            ctx.flush();
        }, config.getFecFlushTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private PeerState peerState(InetSocketAddress recipient, int flowId) {
        PeerKey key = new PeerKey(recipient, flowId);
        PeerState state = peers.get(key);
        if (state != null) {
            state.lastAccessNanos = System.nanoTime();
            return state;
        }
        if (peers.size() >= config.getMaxPeersPerChannel()) {
            cleanupIdlePeers(System.nanoTime());
            if (peers.size() >= config.getMaxPeersPerChannel()) {
                stats.recordPeerLimitDrop();
                return null;
            }
        }
        state = new PeerState(key.recipient);
        peers.put(key, state);
        return state;
    }

    private int effectiveDataMultiplier() {
        if (!config.isRedundantEnabled()) {
            return 1;
        }
        int multiplier = config.getPolicy().getDataMultiplier();
        if (multiplier <= 1) {
            multiplier = config.getRedundantMultiplier();
        }
        return Math.max(1, Math.min(config.getRedundantMaxMultiplier(), multiplier));
    }

    private int effectiveParityMultiplier() {
        if (!config.isRedundantEnabled()) {
            return 1;
        }
        int multiplier = config.getPolicy().getParityMultiplier();
        return Math.max(1, Math.min(config.getRedundantMaxMultiplier(), multiplier));
    }

    private void cleanupIdlePeers(long now) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.getStaleGroupTimeoutMs());
        Iterator<Map.Entry<PeerKey, PeerState>> it = peers.entrySet().iterator();
        while (it.hasNext()) {
            PeerState state = it.next().getValue();
            if (state.isIdle() && now - state.lastAccessNanos > timeoutNanos) {
                state.release();
                it.remove();
            }
        }
    }

    private void releaseAllPeers() {
        for (PeerState state : peers.values()) {
            state.release();
        }
        peers.clear();
    }

    private static final class PeerState {
        final InetSocketAddress recipient;
        int nextSeq = 1;
        int nextGroupId = 1;
        long lastAccessNanos = System.nanoTime();
        FecEncodeGroup group;
        ScheduledFuture<?> flushFuture;

        PeerState(InetSocketAddress recipient) {
            this.recipient = recipient;
        }

        FecEncodeGroup newGroup(int dataShards) {
            group = new FecEncodeGroup(nextGroupId++, dataShards);
            return group;
        }

        void cancelFlush() {
            if (flushFuture != null) {
                flushFuture.cancel(false);
                flushFuture = null;
            }
        }

        void clearGroup() {
            cancelFlush();
            if (group != null) {
                group.release();
                group = null;
            }
        }

        boolean isIdle() {
            return group == null || group.count() == 0;
        }

        void release() {
            clearGroup();
        }
    }

    private static final class PeerKey {
        final InetSocketAddress recipient;
        final int flowId;

        PeerKey(InetSocketAddress recipient, int flowId) {
            this.recipient = UdpProtectAttributes.normalize(recipient);
            this.flowId = flowId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PeerKey)) {
                return false;
            }
            PeerKey that = (PeerKey) o;
            if (flowId != that.flowId) {
                return false;
            }
            return recipient != null ? recipient.equals(that.recipient) : that.recipient == null;
        }

        @Override
        public int hashCode() {
            int result = recipient != null ? recipient.hashCode() : 0;
            result = 31 * result + flowId;
            return result;
        }
    }
}
