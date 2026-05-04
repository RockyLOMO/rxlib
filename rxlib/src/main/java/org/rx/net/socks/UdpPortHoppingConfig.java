package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * UDP SOCKS5 upstream 端口跳跃配置。
 */
@Getter
@Setter
@ToString
@Slf4j
public class UdpPortHoppingConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_HOP_COUNT = 8;
    public static final int RECOMMENDED_MAX_HOP_COUNT = 4;

    private boolean enabled;
    private byte hopCount = 1;
    private byte minActiveHops = 1;
    /**
     * 自适应模式从 minHopCount 起步，达到阈值后逐步增加到 maxHopCount。
     */
    private boolean adaptive;
    private byte minHopCount = 1;
    private byte maxHopCount = 1;
    /**
     * 双向累计 UDP 字节达到该步长后扩容 1 个 hop；0 表示不按流量扩容。
     */
    private long adaptiveScaleUpBytes;
    /**
     * group 活跃时长达到该步长后扩容 1 个 hop；0 表示不按时间扩容。
     */
    private long adaptiveScaleUpActiveMillis;
    private int adaptiveScaleUpCooldownMillis = 1000;
    private UdpPortHoppingMode mode = UdpPortHoppingMode.ROUND_ROBIN;
    private int replenishDelayMillis = 1000;
    /**
     * 预留二期能力。第一期不允许同一冗余组跨端口分散，避免远端重复转发。
     */
    private boolean spreadRedundantCopies;

    public void setHopCount(int hopCount) {
        this.hopCount = (byte) Math.max(1, Math.min(MAX_HOP_COUNT, hopCount));
        warnLargeHopCount("hopCount", this.hopCount, !adaptive);
        if (maxHopCount < this.hopCount) {
            maxHopCount = this.hopCount;
        }
        if (minActiveHops > this.hopCount) {
            minActiveHops = this.hopCount;
        }
    }

    public void setMinActiveHops(int minActiveHops) {
        this.minActiveHops = (byte) Math.max(1, Math.min(MAX_HOP_COUNT, minActiveHops));
    }

    public void setAdaptive(boolean adaptive) {
        this.adaptive = adaptive;
        if (adaptive && maxHopCount < hopCount) {
            maxHopCount = hopCount;
        }
        if (!adaptive) {
            warnLargeHopCount("hopCount", hopCount, true);
        } else {
            warnLargeHopCount("maxHopCount", maxHopCount, false);
        }
    }

    public void setMinHopCount(int minHopCount) {
        this.minHopCount = (byte) Math.max(1, Math.min(MAX_HOP_COUNT, minHopCount));
        if (maxHopCount < this.minHopCount) {
            maxHopCount = this.minHopCount;
        }
    }

    public void setMaxHopCount(int maxHopCount) {
        this.maxHopCount = (byte) Math.max(1, Math.min(MAX_HOP_COUNT, maxHopCount));
        warnLargeHopCount("maxHopCount", this.maxHopCount, false);
        if (minHopCount > this.maxHopCount) {
            minHopCount = this.maxHopCount;
        }
    }

    public void setAdaptiveScaleUpBytes(long adaptiveScaleUpBytes) {
        this.adaptiveScaleUpBytes = Math.max(0L, adaptiveScaleUpBytes);
    }

    public void setAdaptiveScaleUpActiveMillis(long adaptiveScaleUpActiveMillis) {
        this.adaptiveScaleUpActiveMillis = Math.max(0L, adaptiveScaleUpActiveMillis);
    }

    public void setAdaptiveScaleUpCooldownMillis(int adaptiveScaleUpCooldownMillis) {
        this.adaptiveScaleUpCooldownMillis = Math.max(0, adaptiveScaleUpCooldownMillis);
    }

    public void setMode(UdpPortHoppingMode mode) {
        this.mode = mode != null ? mode : UdpPortHoppingMode.ROUND_ROBIN;
    }

    public void setReplenishDelayMillis(int replenishDelayMillis) {
        this.replenishDelayMillis = Math.max(0, replenishDelayMillis);
    }

    private static void warnLargeHopCount(String name, int value, boolean fixedMode) {
        if (value <= RECOMMENDED_MAX_HOP_COUNT) {
            return;
        }
        if (fixedMode) {
            log.warn("UDP port hopping fixed {}={} exceeds recommended {}, control channels and relay ports grow linearly",
                    name, value, RECOMMENDED_MAX_HOP_COUNT);
        } else {
            log.warn("UDP port hopping {}={} exceeds recommended {}, use only for confirmed port-level throttling",
                    name, value, RECOMMENDED_MAX_HOP_COUNT);
        }
    }
}
