package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP 多倍发包解码器（入站 Handler）
 * <p>
 * 对收到的 DatagramPacket 进行去重：读取 8 字节 header 中的 sequenceId，
 * 通过滑动窗口 + 位图判断是否为重复包。
 * <p>
 * 每个 sender 地址独立维护一个去重窗口，避免不同来源的 sequenceId 冲突。
 * <p>
 * 如果绑定了 {@link UdpRedundantStats}，会同步喂入统计数据用于自适应倍率调整。
 * <p>
 * 不可 {@code @Sharable}，每个 channel 持有独立的去重状态。
 */
@Slf4j
public class UdpRedundantDecoder extends MessageToMessageDecoder<DatagramPacket> {
    /**
     * 滑动窗口大小（bit），使用 long 位图，支持最近 64 个序列号去重
     */
    static final int WINDOW_SIZE = 64;

    /**
     * 每个 sender 的去重窗口状态
     */
    static class DeduplicationWindow {
        /**
         * 已见到的最高序列号
         */
        long highestSeq = -1;
        /**
         * 位图：bit[i]=1 表示 (highestSeq - i) 已收到
         * bit 0 = highestSeq, bit 1 = highestSeq-1, ...
         */
        long bitmap = 0;
        /**
         * 最后访问时间（纳秒），用于过期淘汰
         */
        long lastAccessNanos = System.nanoTime();

        /**
         * 检查 sequenceId 是否重复，如果不重复则标记已见。
         *
         * @return true = 新包（应传递），false = 重复包（应丢弃）
         */
        boolean checkAndMark(long seqId) {
            lastAccessNanos = System.nanoTime();

            if (highestSeq < 0) {
                // 首个包
                highestSeq = seqId;
                bitmap = 1L;
                return true;
            }

            // 处理序列号回绕：考虑无符号32位序列号的环形特性
            long diff = calculateSequenceDiff(seqId, highestSeq);
            
            if (diff > 0 && diff <= (1L << 31)) {
                // 新的更高序列号：向前滑动窗口
                if (diff >= WINDOW_SIZE) {
                    // 跳跃太大，重置窗口
                    bitmap = 1L;
                } else {
                    bitmap <<= diff;
                    bitmap |= 1L;
                }
                highestSeq = seqId;
                return true;
            } else if (diff == 0) {
                // 完全相同的序列号 = 重复
                return false;
            } else {
                // diff < 0: 比最高序列号小的旧包，或回绕后的包
                long absDiff = Math.abs(diff);
                if (absDiff >= WINDOW_SIZE) {
                    // 窗口外（太旧），直接丢弃
                    return false;
                }
                long mask = 1L << absDiff;
                if ((bitmap & mask) != 0) {
                    // 已标记 = 重复
                    return false;
                }
                // 新的（窗口内但未见过）
                bitmap |= mask;
                return true;
            }
        }

        /**
         * 计算无符号32位序列号的差值，正确处理回绕
         * @param newSeq 新序列号
         * @param oldSeq 旧序列号
         * @return 差值，正数表示 newSeq > oldSeq，负数表示 newSeq < oldSeq
         */
        private long calculateSequenceDiff(long newSeq, long oldSeq) {
            // 由于是无符号32位，最大差值为 2^31-1
            // 超过这个值认为是反向回绕
            long diff = newSeq - oldSeq;
            if (diff > (1L << 31)) {
                // 正向差值过大，认为是反向回绕
                diff -= (1L << 32);
            } else if (diff < -(1L << 31)) {
                // 负向差值过大，认为是正向回绕
                diff += (1L << 32);
            }
            return diff;
        }
    }

    /**
     * 按 sender 地址维护去重窗口
     */
    private final ConcurrentHashMap<InetSocketAddress, DeduplicationWindow> windows = new ConcurrentHashMap<>();

    /**
     * 自适应统计（可选），为 null 时不采集
     */
    private final UdpRedundantStats stats;

    /**
     * 窗口过期时间（纳秒），超过此时间未活跃的窗口将被清理
     * 默认 10 分钟
     */
    private static final long WINDOW_EXPIRE_NANOS = 10L * 60 * 1_000_000_000L;

    /**
     * 清理计数器，每 N 个包触发一次过期清理
     */
    private int cleanupCounter = 0;
    private static final int CLEANUP_INTERVAL = 1024;

    /**
     * 无自适应统计的构造
     */
    public UdpRedundantDecoder() {
        this(null);
    }

    /**
     * @param stats 自适应统计对象，为 null 时不采集
     */
    public UdpRedundantDecoder(UdpRedundantStats stats) {
        this.stats = stats;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf content = msg.content();

        // 检查是否有足够的 header 长度
        if (content.readableBytes() < UdpRedundantEncoder.HEADER_SIZE) {
            // 不是多倍发包协议的包，直接传递
            out.add(msg.retain());
            return;
        }

        // 预读 magic（不消费 readerIndex）
        int magic = content.getInt(content.readerIndex());
        if (magic != UdpRedundantEncoder.HEADER_MAGIC) {
            // 不是多倍发包协议的包，直接传递（向后兼容）
            out.add(msg.retain());
            return;
        }

        // 消费 header
        content.skipBytes(4); // magic
        int seqId = content.readInt(); // sequenceId

        InetSocketAddress sender = msg.sender();
        DeduplicationWindow window = windows.computeIfAbsent(sender, k -> new DeduplicationWindow());

        // 使用 int → long 的无符号扩展，避免 int 溢出导致序列号回绕问题
        long seqLong = seqId & 0xFFFFFFFFL;

        // 每收到一个包（含冗余副本）都计入 totalReceived
        if (stats != null) {
            stats.recordReceived();
        }

        if (!window.checkAndMark(seqLong)) {
            // 重复包，丢弃
            if (log.isDebugEnabled()) {
                log.debug("UDP redundant discard duplicate seq={} from {}", seqId, sender);
            }
            return;
        }

        // 新包（去重后）计入 uniqueReceived
        if (stats != null) {
            stats.recordUnique();
        }

        // 新包：构造剥离 header 后的 DatagramPacket 传递到下游
        out.add(new DatagramPacket(content.retain(), msg.recipient(), sender));

        // 定期清理过期窗口
        if (++cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            cleanupStaleWindows();
        }
    }

    /**
     * 清理长时间未活跃的 sender 窗口，防止内存泄漏
     */
    private void cleanupStaleWindows() {
        long now = System.nanoTime();
        windows.entrySet().removeIf(entry -> (now - entry.getValue().lastAccessNanos) > WINDOW_EXPIRE_NANOS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        windows.clear();
        super.channelInactive(ctx);
    }
}
