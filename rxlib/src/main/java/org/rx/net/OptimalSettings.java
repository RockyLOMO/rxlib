package org.rx.net;

import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.internal.PlatformDependent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.Constants;

import java.io.Serializable;

@ToString
public class OptimalSettings implements Serializable {
    private static final long serialVersionUID = 7463099214399469671L;

    @RequiredArgsConstructor
    public enum Mode {
        //192K
        LOW_LATENCY(0.5, 256 * 1024),  // 积极回压，小缓冲区
        BALANCED(1, 1024 * 1024),
        HIGH_THROUGHPUT(1.5, 2 * 1024 * 1024); // 延迟回压，大缓冲区

        final double factor;
        final int maxHighWaterMark;
    }

    public static final OptimalSettings EMPTY = new OptimalSettings(0, 0, 0, null) {
        @Override
        public void calculate(int usableMemoryMB, int rttMillis, int perConnSpeedMbps, int maxConnections, double writeBufferRatio, Mode mode, Integer backlogMemoryMB) {
            writeBufferWaterMark = WriteBufferWaterMark.DEFAULT;
            recvByteBufAllocator = AdaptiveRecvByteBufAllocator.DEFAULT;
            backlog = 2048;
        }
    };

    @Getter
    @Setter
    int rttMillis;
    @Getter
    @Setter
    int perConnSpeedMbps;
    @Getter
    @Setter
    int maxConnections;
    @Getter
    @Setter
    Mode mode;

    transient WriteBufferWaterMark writeBufferWaterMark;
    transient AdaptiveRecvByteBufAllocator recvByteBufAllocator;
    transient int backlog;

    public OptimalSettings(int rttMillis, int perConnSpeedMbps, int maxConnections, Mode mode) {
        this.rttMillis = rttMillis;
        this.perConnSpeedMbps = perConnSpeedMbps;
        this.maxConnections = maxConnections;
        this.mode = mode;
    }

    public void calculate() {
        calculate(0.75, rttMillis, perConnSpeedMbps, maxConnections, mode);
    }

    public void calculate(double bufferAllocationRatio, int rttMillis, int perConnSpeedMbps, int maxConnections, Mode mode) {
        int usableMemoryMB = (int) (PlatformDependent.maxDirectMemory() / Constants.MB * Math.min(Math.max(bufferAllocationRatio, 0.8), 0.1));
        calculate(usableMemoryMB, rttMillis, perConnSpeedMbps, maxConnections, mode);
    }

    public void calculate(int usableMemoryMB, int rttMillis, int perConnSpeedMbps, int maxConnections, Mode mode) {
        calculate(usableMemoryMB, rttMillis, perConnSpeedMbps, maxConnections, 1d, mode, null);
    }

    /**
     * 计算 Netty 缓冲区的优化设置
     *
     * @param usableMemoryMB   MaxDirectMemorySize
     * @param rttMillis        RTT 延迟 (毫秒)
     * @param perConnSpeedMbps 单连接预期带宽（Mbps）
     * @param maxConnections   预期最大并发连接数
     * @param writeBufferRatio 写buffer占比
     * @param mode             优化模式 (低延迟或高吞吐)
     * @return 包含最佳 WriteBufferWaterMark 和 RecvByteBufAllocator 的 Map
     */
    public void calculate(int usableMemoryMB,
                          int rttMillis, int perConnSpeedMbps, int maxConnections, double writeBufferRatio, Mode mode,
                          Integer backlogMemoryMB) {
        if (usableMemoryMB <= 0) {
            throw new IllegalArgumentException("usableMemoryMB");
        }
        if (rttMillis <= 0) {
            throw new IllegalArgumentException("rttMillis");
        }
        if (perConnSpeedMbps <= 0) {
            throw new IllegalArgumentException("perConnSpeedMbps");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections");
        }
        if (writeBufferRatio <= 0 || writeBufferRatio > 1) {
            writeBufferRatio = 1;
        }
        if (mode == null) {
            mode = Mode.BALANCED;
        }

        // --- I. WriteBufferWaterMark (HWM & LWM) ---
        // A. 1. 理论 BDP (网络效率要求)
        double perConnBytesPerSec = perConnSpeedMbps * Constants.MB / 8d;
        double rttSec = rttMillis / 1000D;
        double sMinBDP = perConnBytesPerSec * rttSec;

        // A. 2. 资源约束要求 (每个连接的预算)
        double usableMemoryBytes = usableMemoryMB * Constants.MB;
        double perConnMaxBudget = usableMemoryBytes / maxConnections;

        // A. 3. 基础高水位 (取两者最小值，并应用模式因子)
        double hBase = Math.min(sMinBDP, perConnMaxBudget * writeBufferRatio);

        // 确保 HWM 至少为 64KB (Netty 默认值)，且不超过 1MB
        double hLimiter = Math.max(hBase, Constants.SIZE_64K);
        hLimiter = Math.min(hLimiter, mode.maxHighWaterMark);

        // A. 4. 最终 HWM (向上取整到 2 的幂次方)
        int finalHWM = roundUpPowerOfTwo(hLimiter * mode.factor);

        // A. 5. 最终 LWM
        int finalLWM = finalHWM / 2; // 默认使用 HWM 的一半

        writeBufferWaterMark = new WriteBufferWaterMark(finalLWM, finalHWM);

        // --- II. AdaptiveRecvByteBufAllocator (读取分配器) ---
        //应对最小的 TCP/HTTP 控制包或 ACK。Netty 默认自适应分配器的最小值是 64，但 512 更常见且高效。
        int rMin = 512;
        //经典的 4KB 是 Linux 文件系统和操作系统的常用页面大小，也是大多数网络应用中常见的数据块或 TCP MSS 的倍数。
        int rInit = 4096;
        // 64KB 是 Netty 的默认上限，也是操作系统的 TCP 接收窗口的常见最大值。足以应对大部分大数据块的接收。
        int rMax = Constants.SIZE_64K;

        recvByteBufAllocator = new AdaptiveRecvByteBufAllocator(rMin, rInit, rMax);

        // --- C. SO_BACKLOG (监听队列大小) ---
        if (backlogMemoryMB == null) {
            backlogMemoryMB = (int) (usableMemoryMB * 0.02);
        }
        // 单个 Backlog 连接的保守内存开销 (2KB)
        final int COST_PER_BACKLOG_CONN = 2048;

        // 确保 Backlog 至少有一个经验值 (如 4096)
        backlog = Math.max((backlogMemoryMB * Constants.MB / COST_PER_BACKLOG_CONN), 2048);
    }

    /**
     * 将数字向上取整到最近的 2 的幂次方 (Netty 推荐)
     */
    private static int roundUpPowerOfTwo(double value) {
        if (value <= 0) {
            return Constants.SIZE_64K; // 至少 64KB
        }
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }
}
