package org.rx.net.socks;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * UDP 多倍发包自适应统计
 * <p>
 * 在 Encoder 和 Decoder 之间共享，Decoder 喂入统计数据，
 * Encoder 定期读取丢包率并调整发送倍率。
 * <p>
 * 丢包率计算原理：
 * <pre>
 * redundancy_ratio  = totalReceived / uniqueReceived
 * per_copy_loss     = 1 - (redundancy_ratio / currentMultiplier)
 * </pre>
 * 如果 multiplier=3 且平均只收到 1.5 份副本，则 per_copy_loss = 1 - (1.5/3) = 50%。
 */
@Slf4j
public class UdpRedundantStats {
    /**
     * 采样窗口内总收到包数（含所有冗余副本）
     */
    private final LongAdder totalReceived = new LongAdder();
    /**
     * 采样窗口内去重后的唯一包数
     */
    private final LongAdder uniqueReceived = new LongAdder();

    /**
     * 当前动态倍率
     */
    private final AtomicInteger currentMultiplier;
    /**
     * 最小倍率（自适应下限），1 = 可完全关闭冗余
     */
    @Getter
    private final int minMultiplier;
    /**
     * 最大倍率（自适应上限）
     */
    @Getter
    private final int maxMultiplier;
    /**
     * 丢包率上阈值：超过此值增加倍率
     */
    @Getter
    private final double lossThresholdHigh;
    /**
     * 丢包率下阈值：低于此值降低倍率
     */
    @Getter
    private final double lossThresholdLow;
    /**
     * 防抖：连续多少个调整周期满足条件才实际调整
     */
    @Getter
    private final int stablePeriodsRequired;
    /**
     * 冗余副本间隔微秒
     */
    @Getter
    private final int intervalMicros;

    // 防抖状态
    private int stableUpCount = 0;
    private int stableDownCount = 0;

    // 最后一次计算的丢包率（用于日志 / 监控）
    @Getter
    private volatile double lastLossRate = 0;

    /**
     * @param initialMultiplier 初始倍率
     * @param minMultiplier     最小倍率（自适应下限）
     * @param maxMultiplier     最大倍率（自适应上限）
     * @param intervalMicros    冗余副本间隔微秒
     * @param lossThresholdHigh 丢包率上阈值（如 0.20）
     * @param lossThresholdLow  丢包率下阈值（如 0.05）
     * @param stablePeriodsRequired 防抖周期数
     */
    public UdpRedundantStats(int initialMultiplier, int minMultiplier, int maxMultiplier,
                             int intervalMicros,
                             double lossThresholdHigh, double lossThresholdLow,
                             int stablePeriodsRequired) {
        this.currentMultiplier = new AtomicInteger(Math.max(1, initialMultiplier));
        this.minMultiplier = Math.max(1, minMultiplier);
        this.maxMultiplier = Math.min(5, Math.max(this.minMultiplier, maxMultiplier));
        this.intervalMicros = Math.max(0, intervalMicros);
        this.lossThresholdHigh = lossThresholdHigh;
        this.lossThresholdLow = lossThresholdLow;
        this.stablePeriodsRequired = Math.max(1, stablePeriodsRequired);
    }

    /**
     * 获取当前动态倍率
     */
    public int getMultiplier() {
        return currentMultiplier.get();
    }

    /**
     * Decoder 收到一个包时调用（包含冗余副本）
     */
    public void recordReceived() {
        totalReceived.increment();
    }

    /**
     * Decoder 收到一个新包（去重后）时调用
     */
    public void recordUnique() {
        uniqueReceived.increment();
    }

    /**
     * 计算当前丢包率并调整倍率。
     * 由 Encoder 定期调用（如每 2 秒一次）。
     *
     * @return 调整后的倍率
     */
    public synchronized int adjustMultiplier() {
        long total = totalReceived.sumThenReset();
        long unique = uniqueReceived.sumThenReset();

        if (unique == 0) {
            // 没有数据，不调整
            return currentMultiplier.get();
        }

        int mult = currentMultiplier.get();
        double redundancyRatio = (double) total / unique;
        double lossRate = 1.0 - (redundancyRatio / mult);
        // 因为回程 multiplier 可能和本端不同，所以 clamp 到 [0, 1]
        lossRate = Math.max(0, Math.min(1.0, lossRate));
        this.lastLossRate = lossRate;

        if (lossRate > lossThresholdHigh) {
            stableDownCount = 0;
            if (++stableUpCount >= stablePeriodsRequired) {
                int newMult = Math.min(maxMultiplier, mult + 1);
                if (newMult != mult) {
                    currentMultiplier.set(newMult);
                    log.info("UDP redundant adaptive UP: loss={}, {} -> {}", String.format("%.1f%%", lossRate * 100), mult, newMult);
                }
                stableUpCount = 0;
            }
        } else if (lossRate < lossThresholdLow) {
            stableUpCount = 0;
            if (++stableDownCount >= stablePeriodsRequired) {
                int newMult = Math.max(minMultiplier, mult - 1);
                if (newMult != mult) {
                    currentMultiplier.set(newMult);
                    log.info("UDP redundant adaptive DOWN: loss={}, {} -> {}", String.format("%.1f%%", lossRate * 100), mult, newMult);
                }
                stableDownCount = 0;
            }
        } else {
            // 中间区间，重置防抖
            stableUpCount = 0;
            stableDownCount = 0;
        }

        if (log.isDebugEnabled()) {
            log.debug("UDP redundant stats: total={}, unique={}, ratio={}, loss={}, mult={}",
                    total, unique, String.format("%.2f", redundancyRatio),
                    String.format("%.1f%%", lossRate * 100), currentMultiplier.get());
        }
        return currentMultiplier.get();
    }
}
