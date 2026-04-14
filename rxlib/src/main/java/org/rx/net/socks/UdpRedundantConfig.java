package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP 多倍发包配置
 * <p>
 * 支持静态倍率、自适应调整和分目的地规则三种模式。
 * 可独立配置，也可嵌入到 {@link SocksConfig} 中使用。
 */
@Getter
@Setter
@ToString
public class UdpRedundantConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 静态倍率或自适应初始倍率。
     * 取值范围 [1, 5]，默认 1。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     */
    private byte multiplier = 1;

    /**
     * 冗余副本之间的发送间隔（微秒）。
     * 0 = 同一时刻发送（默认）；> 0 = 每个冗余副本间隔发送。
     * 建议 200~1000μs，用于应对突发丢包（burst loss）。
     */
    private int intervalMicros = 0;

    /**
     * 是否启用自适应倍率调整。
     * 启用后根据实际丢包率动态调整 multiplier，范围在 [minMultiplier, maxMultiplier] 之间。
     * multiplier 作为初始值。
     */
    private boolean adaptive = false;

    /**
     * 自适应模式最小倍率。默认 1 = 网络好时可完全关闭冗余。
     */
    private byte minMultiplier = 1;

    /**
     * 自适应模式最大倍率。默认 5。
     */
    private byte maxMultiplier = 5;

    /**
     * 自适应丢包率上阈值（0~1）。超过此值增加倍率。默认 0.20（20%）。
     */
    private double lossThresholdHigh = 0.20;

    /**
     * 自适应丢包率下阈值（0~1）。低于此值降低倍率。默认 0.05（5%）。
     */
    private double lossThresholdLow = 0.05;

    /**
     * 防抖周期数。连续多少个调整周期（每周期 2 秒）满足条件才实际调整。默认 3。
     */
    private short stablePeriods = 3;

    /**
     * 分目的地倍率规则，列表顺序为优先级（先匹配先生效）。
     * 命中规则时覆盖全局 multiplier 或自适应倍率；未命中则使用全局配置。
     */
    private List<UdpRedundantDestinationRule> destinationRules = new ArrayList<>();

    /**
     * 构建当前配置下的目的地倍率解析器；无规则时始终返回 {@link UdpRedundantMultiplierResolver#NO_MATCH}。
     */
    public UdpRedundantMultiplierResolver buildMultiplierResolver() {
        List<UdpRedundantDestinationRule> rules = destinationRules;
        if (rules == null || rules.isEmpty()) {
            return dst -> UdpRedundantMultiplierResolver.NO_MATCH;
        }
        List<UdpRedundantDestinationRule> snapshot = new ArrayList<>(rules);
        return destination -> {
            if (destination == null) {
                return UdpRedundantMultiplierResolver.NO_MATCH;
            }
            for (UdpRedundantDestinationRule r : snapshot) {
                if (r != null && r.matches(destination)) {
                    return r.getMultiplier();
                }
            }
            return UdpRedundantMultiplierResolver.NO_MATCH;
        };
    }

    /**
     * 是否配置了至少一条分目的地规则（用于决定是否安装冗余 Handler）。
     */
    public boolean hasDestinationRules() {
        return destinationRules != null && !destinationRules.isEmpty();
    }

    /**
     * 设置倍率，自动限制在 [1, 5] 范围内。
     */
    public void setMultiplier(int multiplier) {
        this.multiplier = (byte) Math.max(1, Math.min(5, multiplier));
    }

    /**
     * 设置最小倍率，确保不超过最大倍率。
     */
    public void setMinMultiplier(int minMultiplier) {
        this.minMultiplier = (byte) Math.max(1, minMultiplier);
    }

    /**
     * 设置最大倍率，确保不小于最小倍率且不超过 5。
     */
    public void setMaxMultiplier(int maxMultiplier) {
        this.maxMultiplier = (byte) Math.max(this.minMultiplier, Math.min(5, maxMultiplier));
    }

    /**
     * 设置丢包率上阈值，确保在 [0, 1] 范围内。
     */
    public void setLossThresholdHigh(double lossThresholdHigh) {
        this.lossThresholdHigh = Math.max(0.0, Math.min(1.0, lossThresholdHigh));
    }

    /**
     * 设置丢包率下阈值，确保在 [0, 1] 范围内。
     */
    public void setLossThresholdLow(double lossThresholdLow) {
        this.lossThresholdLow = Math.max(0.0, Math.min(1.0, lossThresholdLow));
    }

    /**
     * 设置防抖周期数，确保至少为 1。
     */
    public void setStablePeriods(int stablePeriods) {
        this.stablePeriods = (short) Math.max(1, stablePeriods);
    }

    /**
     * 设置发送间隔，确保非负。
     */
    public void setIntervalMicros(int intervalMicros) {
        this.intervalMicros = Math.max(0, intervalMicros);
    }

    /**
     * 从 SocksConfig 复制 UDP 冗余配置到独立对象。
     */
    public static UdpRedundantConfig fromSocksConfig(SocksConfig socksConfig) {
        UdpRedundantConfig config = new UdpRedundantConfig();
        config.setMultiplier(socksConfig.getUdpRedundantMultiplier());
        config.setIntervalMicros(socksConfig.getUdpRedundantIntervalMicros());
        config.setAdaptive(socksConfig.isUdpRedundantAdaptive());
        config.setMinMultiplier(socksConfig.getUdpRedundantMinMultiplier());
        config.setMaxMultiplier(socksConfig.getUdpRedundantMaxMultiplier());
        config.setLossThresholdHigh(socksConfig.getUdpRedundantLossThresholdHigh());
        config.setLossThresholdLow(socksConfig.getUdpRedundantLossThresholdLow());
        config.setStablePeriods(socksConfig.getUdpRedundantStablePeriods());
        config.getDestinationRules().addAll(socksConfig.getUdpRedundantDestinationRules());
        return config;
    }

    /**
     * 将配置应用到 SocksConfig（用于向后兼容）。
     */
    public void applyToSocksConfig(SocksConfig socksConfig) {
        socksConfig.setUdpRedundantMultiplier(multiplier);
        socksConfig.setUdpRedundantIntervalMicros(intervalMicros);
        socksConfig.setUdpRedundantAdaptive(adaptive);
        socksConfig.setUdpRedundantMinMultiplier(minMultiplier);
        socksConfig.setUdpRedundantMaxMultiplier(maxMultiplier);
        socksConfig.setUdpRedundantLossThresholdHigh(lossThresholdHigh);
        socksConfig.setUdpRedundantLossThresholdLow(lossThresholdLow);
        socksConfig.setUdpRedundantStablePeriods(stablePeriods);
        socksConfig.getUdpRedundantDestinationRules().clear();
        socksConfig.getUdpRedundantDestinationRules().addAll(destinationRules);
    }
}
