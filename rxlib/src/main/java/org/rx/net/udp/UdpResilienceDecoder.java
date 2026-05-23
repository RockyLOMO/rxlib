package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * UDP Resilience 入站解码器。先去重，再进入 FEC 分组，避免多倍副本污染恢复状态。
 */
@Slf4j
public class UdpResilienceDecoder extends ChannelInboundHandlerAdapter {
    private static final int CLEANUP_INTERVAL = 1024;

    private final UdpResilienceConfig config;
    private final UdpResilienceStats stats;
    private final HashMap<DedupKey, UdpDedupWindow> dedupWindows = new HashMap<>();
    private final HashMap<FecGroupKey, FecDecodeGroup> groups = new HashMap<>();
    private final HashMap<PeerSessionKey, Integer> groupCounts = new HashMap<>();
    private final LinkedHashMap<FecGroupKey, Long> completedGroups = new LinkedHashMap<>(64, 0.75f, true);
    private int cleanupCounter;

    public UdpResilienceDecoder(UdpResilienceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.stats = config.stats();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.channelRead(ctx, msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf content = packet.content();
        if (!UdpResilienceHeader.isResilienceFrame(content)) {
            super.channelRead(ctx, msg);
            return;
        }

        try {
            decodeResilience(ctx, packet);
        } finally {
            packet.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseAll();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releaseAll();
        super.handlerRemoved(ctx);
    }

    private void decodeResilience(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf content = packet.content();
        int readerIndex = content.readerIndex();
        int readable = content.readableBytes();
        if (!validBaseHeader(content, readerIndex, readable)) {
            stats.recordDecodeDrop();
            return;
        }

        int flags = UdpResilienceHeader.flags(content, readerIndex);
        int headerLen = UdpResilienceHeader.headerLength(content, readerIndex);
        int codec = UdpResilienceHeader.codec(content, readerIndex);
        int shardK = UdpResilienceHeader.shardK(content, readerIndex);
        int shardP = UdpResilienceHeader.shardP(content, readerIndex);
        int shardIdx = UdpResilienceHeader.shardIdx(content, readerIndex);
        int sessionId = UdpResilienceHeader.sessionId(content, readerIndex);
        int seq = UdpResilienceHeader.seq(content, readerIndex);
        int groupId = UdpResilienceHeader.groupId(content, readerIndex);
        int payloadLen = UdpResilienceHeader.payloadLen(content, readerIndex);
        int payloadIndex = readerIndex + headerLen;
        int payloadBytes = readable - headerLen;
        InetSocketAddress sender = packet.sender();

        if (sender == null || !validPacket(flags, codec, shardK, shardP, shardIdx, payloadLen, payloadBytes)) {
            stats.recordDecodeDrop();
            return;
        }

        if (++cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            cleanupStale(System.nanoTime());
        }

        stats.recordReceivedResilience();
        DedupKey dedupKey = new DedupKey(sender, sessionId);
        UdpDedupWindow window = dedupWindow(dedupKey);
        if (window == null) {
            stats.recordPeerLimitDrop();
            return;
        }
        if (window.isDuplicate(seq & 0xFFFFFFFFL)) {
            stats.recordDuplicateDrop();
            return;
        }

        FecGroupKey groupKey = new FecGroupKey(sender, sessionId, groupId);
        if (isCompleted(groupKey)) {
            return;
        }

        if ((flags & UdpResilienceHeader.FLAG_DATA) != 0) {
            handleData(ctx, packet, content, payloadIndex, payloadLen, codec, shardK, shardIdx, groupKey);
        } else if ((flags & UdpResilienceHeader.FLAG_PARITY) != 0) {
            handleParity(ctx, packet, content, payloadIndex, payloadBytes, shardK, groupKey);
        } else {
            stats.recordDecodeDrop();
        }
    }

    private boolean validBaseHeader(ByteBuf content, int readerIndex, int readable) {
        if (readable < UdpResilienceHeader.HEADER_SIZE) {
            return false;
        }
        if (UdpResilienceHeader.version(content, readerIndex) != UdpResilienceHeader.VERSION) {
            return false;
        }
        int headerLen = UdpResilienceHeader.headerLength(content, readerIndex);
        return headerLen >= UdpResilienceHeader.HEADER_SIZE && readable >= headerLen;
    }

    private boolean validPacket(int flags, int codec, int shardK, int shardP, int shardIdx,
                                int payloadLen, int payloadBytes) {
        boolean data = (flags & UdpResilienceHeader.FLAG_DATA) != 0;
        boolean parity = (flags & UdpResilienceHeader.FLAG_PARITY) != 0;
        if (data == parity) {
            return false;
        }
        if (parity && codec != UdpResilienceHeader.CODEC_XOR) {
            return false;
        }
        if (codec != UdpResilienceHeader.CODEC_NONE && codec != UdpResilienceHeader.CODEC_XOR) {
            return false;
        }
        if (codec == UdpResilienceHeader.CODEC_XOR && (shardK <= 0 || shardK > config.getFecDataShards() || shardP != 1)) {
            return false;
        }
        if (data) {
            return shardIdx >= 0 && shardIdx < Math.max(1, shardK)
                    && payloadLen <= payloadBytes && payloadLen <= config.getMaxResiliencePayload();
        }
        return payloadBytes > 0 && shardIdx >= shardK;
    }

    private void handleData(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf content,
                            int payloadIndex, int payloadLen, int codec, int shardK,
                            int shardIdx, FecGroupKey groupKey) {
        ByteBuf payload = content.retainedSlice(payloadIndex, payloadLen);
        ctx.fireChannelRead(new DatagramPacket(payload, packet.recipient(), packet.sender()));
        stats.recordDelivered();

        if (!config.isFecEnabled() || codec != UdpResilienceHeader.CODEC_XOR) {
            return;
        }
        FecDecodeGroup group = groupFor(groupKey, shardK);
        if (group == null) {
            stats.recordGroupLimitDrop();
            return;
        }

        ByteBuf block = ctx.alloc().directBuffer(2 + payloadLen);
        try {
            block.writeShort(payloadLen);
            block.writeBytes(content, payloadIndex, payloadLen);
            group.addData(shardIdx, block);
            block = null;
        } finally {
            if (block != null) {
                block.release();
            }
        }
        tryRecoverOrComplete(ctx, packet, groupKey, group);
    }

    private void handleParity(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf content,
                              int payloadIndex, int payloadBytes, int shardK, FecGroupKey groupKey) {
        if (!config.isFecEnabled()) {
            return;
        }
        FecDecodeGroup group = groupFor(groupKey, shardK);
        if (group == null) {
            stats.recordGroupLimitDrop();
            return;
        }

        ByteBuf block = ctx.alloc().directBuffer(payloadBytes);
        try {
            block.writeBytes(content, payloadIndex, payloadBytes);
            group.addParity(block, shardK);
            block = null;
        } finally {
            if (block != null) {
                block.release();
            }
        }
        tryRecoverOrComplete(ctx, packet, groupKey, group);
    }

    private void tryRecoverOrComplete(ChannelHandlerContext ctx, DatagramPacket packet,
                                      FecGroupKey groupKey, FecDecodeGroup group) {
        ByteBuf recovered = group.tryRecover(ctx.alloc(), config.getMaxResiliencePayload());
        if (recovered != null) {
            ctx.fireChannelRead(new DatagramPacket(recovered, packet.recipient(), packet.sender()));
            stats.recordRecovered();
            stats.recordDelivered();
            completeGroup(groupKey);
            return;
        }
        if (group.isComplete()) {
            completeGroup(groupKey);
        }
    }

    private UdpDedupWindow dedupWindow(DedupKey key) {
        UdpDedupWindow window = dedupWindows.get(key);
        if (window != null) {
            return window;
        }
        if (dedupWindows.size() >= config.getMaxPeersPerChannel()) {
            cleanupStale(System.nanoTime());
            if (dedupWindows.size() >= config.getMaxPeersPerChannel()) {
                return null;
            }
        }
        window = new UdpDedupWindow();
        dedupWindows.put(key, window);
        return window;
    }

    private FecDecodeGroup groupFor(FecGroupKey key, int shardK) {
        FecDecodeGroup group = groups.get(key);
        if (group != null) {
            return group;
        }
        PeerSessionKey peer = new PeerSessionKey(key.getSender(), key.getSessionId());
        Integer count = groupCounts.get(peer);
        int current = count != null ? count : 0;
        if (current >= config.getMaxGroupsPerPeer()) {
            return null;
        }
        group = new FecDecodeGroup(key, config.getFecDataShards(), shardK);
        groups.put(key, group);
        groupCounts.put(peer, current + 1);
        return group;
    }

    private void completeGroup(FecGroupKey key) {
        FecDecodeGroup removed = groups.remove(key);
        if (removed != null) {
            removed.release();
            decrementGroupCount(key);
        }
        completedGroups.put(key, System.nanoTime());
    }

    private boolean isCompleted(FecGroupKey key) {
        Long time = completedGroups.get(key);
        if (time == null) {
            return false;
        }
        if (System.nanoTime() - time > TimeUnit.MILLISECONDS.toNanos(config.getStaleGroupTimeoutMs())) {
            completedGroups.remove(key);
            return false;
        }
        return true;
    }

    private void decrementGroupCount(FecGroupKey key) {
        PeerSessionKey peer = new PeerSessionKey(key.getSender(), key.getSessionId());
        Integer count = groupCounts.get(peer);
        if (count == null || count <= 1) {
            groupCounts.remove(peer);
        } else {
            groupCounts.put(peer, count - 1);
        }
    }

    private void cleanupStale(long now) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.getStaleGroupTimeoutMs());
        Iterator<Map.Entry<FecGroupKey, FecDecodeGroup>> git = groups.entrySet().iterator();
        while (git.hasNext()) {
            Map.Entry<FecGroupKey, FecDecodeGroup> entry = git.next();
            FecDecodeGroup group = entry.getValue();
            if (now - group.createTimeNanos > timeoutNanos) {
                group.release();
                git.remove();
                decrementGroupCount(entry.getKey());
            }
        }

        Iterator<Map.Entry<FecGroupKey, Long>> cit = completedGroups.entrySet().iterator();
        while (cit.hasNext()) {
            Map.Entry<FecGroupKey, Long> entry = cit.next();
            if (now - entry.getValue() > timeoutNanos) {
                cit.remove();
            }
        }

        Iterator<Map.Entry<DedupKey, UdpDedupWindow>> dit = dedupWindows.entrySet().iterator();
        while (dit.hasNext()) {
            Map.Entry<DedupKey, UdpDedupWindow> entry = dit.next();
            if (now - entry.getValue().lastAccessNanos() > timeoutNanos) {
                dit.remove();
            }
        }
    }

    private void releaseAll() {
        for (FecDecodeGroup group : groups.values()) {
            group.release();
        }
        groups.clear();
        groupCounts.clear();
        completedGroups.clear();
        dedupWindows.clear();
    }

    private static final class DedupKey {
        final InetSocketAddress sender;
        final int sessionId;

        DedupKey(InetSocketAddress sender, int sessionId) {
            this.sender = UdpResilienceAttributes.normalize(sender);
            this.sessionId = sessionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DedupKey)) {
                return false;
            }
            DedupKey that = (DedupKey) o;
            if (sessionId != that.sessionId) {
                return false;
            }
            return sender != null ? sender.equals(that.sender) : that.sender == null;
        }

        @Override
        public int hashCode() {
            int result = sender != null ? sender.hashCode() : 0;
            result = 31 * result + sessionId;
            return result;
        }
    }

    private static final class PeerSessionKey {
        final InetSocketAddress sender;
        final int sessionId;

        PeerSessionKey(InetSocketAddress sender, int sessionId) {
            this.sender = UdpResilienceAttributes.normalize(sender);
            this.sessionId = sessionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PeerSessionKey)) {
                return false;
            }
            PeerSessionKey that = (PeerSessionKey) o;
            if (sessionId != that.sessionId) {
                return false;
            }
            return sender != null ? sender.equals(that.sender) : that.sender == null;
        }

        @Override
        public int hashCode() {
            int result = sender != null ? sender.hashCode() : 0;
            result = 31 * result + sessionId;
            return result;
        }
    }
}
