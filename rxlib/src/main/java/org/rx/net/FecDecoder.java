package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * FEC 解码器 (Inbound Handler)
 * <p>
 * 解析入站 FEC 数据包，缓冲组数据。
 * 如果某组缺少 1 个数据包且奇偶校验包已到达，则通过 XOR 恢复丢失的数据包。
 */
@Slf4j
public class FecDecoder extends ChannelInboundHandlerAdapter {
    /**
     * FEC 组状态
     */
    static class FecGroup {
        final int groupId;
        final int groupSize;
        final byte[][] dataPackets;
        final boolean[] received;
        byte[] parityPayload;
        int receivedCount;
        int maxPayloadLen;
        long createTime;

        FecGroup(int groupId, int groupSize) {
            this.groupId = groupId;
            this.groupSize = groupSize;
            this.dataPackets = new byte[groupSize][];
            this.received = new boolean[groupSize];
            this.createTime = System.currentTimeMillis();
        }

        boolean isComplete() {
            return receivedCount >= groupSize;
        }

        /**
         * 尝试恢复丢失的数据包
         *
         * @return 恢复的数据包 payload，如果无法恢复返回 null
         */
        byte[] tryRecover() {
            if (parityPayload == null) {
                return null;
            }
            // 找到唯一丢失的包
            int missingIdx = -1;
            int missingCount = 0;
            for (int i = 0; i < groupSize; i++) {
                if (!received[i]) {
                    missingIdx = i;
                    missingCount++;
                }
            }
            if (missingCount != 1) {
                // XOR 只能恢复 1 个丢失包
                return null;
            }

            // recovered = parity XOR all_other_data
            byte[] recovered = new byte[maxPayloadLen];
            System.arraycopy(parityPayload, 0, recovered, 0, Math.min(parityPayload.length, recovered.length));
            for (int i = 0; i < groupSize; i++) {
                if (i == missingIdx || dataPackets[i] == null) {
                    continue;
                }
                for (int j = 0; j < dataPackets[i].length; j++) {
                    recovered[j] ^= dataPackets[i][j];
                }
            }
            log.info("FEC recovered packet groupId={}, idx={}", groupId, missingIdx);
            return recovered;
        }
    }

    private final int groupSize;
    private final long staleGroupTimeoutMs;
    private final Map<Integer, FecGroup> groups = new ConcurrentHashMap<>();
    private ScheduledFuture<?> evictionTimer;

    public FecDecoder(FecConfig config) {
        this.groupSize = config.getGroupSize();
        this.staleGroupTimeoutMs = config.getStaleGroupTimeoutMs();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduleEviction(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelEviction();
        groups.clear();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.channelRead(ctx, msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf content = packet.content();
        InetSocketAddress sender = packet.sender();

        // 检查是否 FEC 包 (Magic check)
        if (content.readableBytes() < FecPacket.HEADER_SIZE) {
            super.channelRead(ctx, msg);
            return;
        }
        content.markReaderIndex();
        short magic = content.readShort();
        content.resetReaderIndex();
        if (magic != FecPacket.MAGIC) {
            super.channelRead(ctx, msg);
            return;
        }

        FecPacket fecPkt = FecPacket.decode(content);
        if (fecPkt == null) {
            packet.release();
            return;
        }
        packet.release();

        FecGroup group = groups.computeIfAbsent(fecPkt.getGroupId(), k -> new FecGroup(k, groupSize));

        if (fecPkt.isParity()) {
            group.parityPayload = fecPkt.getPayload();
            if (fecPkt.getPayload().length > group.maxPayloadLen) {
                group.maxPayloadLen = fecPkt.getPayload().length;
            }
            // 检查是否能恢复丢失的数据包
            byte[] recovered = group.tryRecover();
            if (recovered != null) {
                ctx.fireChannelRead(new DatagramPacket(Unpooled.wrappedBuffer(recovered), null, sender));
                groups.remove(fecPkt.getGroupId());
            }
        } else {
            int idx = fecPkt.getGroupIdx();
            if (idx >= 0 && idx < groupSize && !group.received[idx]) {
                group.dataPackets[idx] = fecPkt.getPayload();
                group.received[idx] = true;
                group.receivedCount++;
                if (fecPkt.getPayload().length > group.maxPayloadLen) {
                    group.maxPayloadLen = fecPkt.getPayload().length;
                }
            }

            // 数据包立即传递给上层
            ctx.fireChannelRead(new DatagramPacket(Unpooled.wrappedBuffer(fecPkt.getPayload()), null, sender));

            if (group.isComplete()) {
                groups.remove(fecPkt.getGroupId());
            }
        }
    }

    private void scheduleEviction(ChannelHandlerContext ctx) {
        if (staleGroupTimeoutMs <= 0) {
            return;
        }
        evictionTimer = ctx.executor().scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            groups.entrySet().removeIf(entry -> {
                boolean stale = now - entry.getValue().createTime > staleGroupTimeoutMs;
                if (stale) {
                    log.debug("FEC evicting stale group {}", entry.getKey());
                }
                return stale;
            });
        }, staleGroupTimeoutMs, staleGroupTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelEviction() {
        if (evictionTimer != null) {
            evictionTimer.cancel(false);
            evictionTimer = null;
        }
    }
}
