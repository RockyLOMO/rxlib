package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * UDP SOCKS5 upstream 端口跳跃配置。
 */
@Getter
@Setter
@ToString
public class UdpPortHoppingConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_HOP_COUNT = 8;

    private boolean enabled;
    private byte hopCount = 1;
    private byte minActiveHops = 1;
    private UdpPortHoppingMode mode = UdpPortHoppingMode.ROUND_ROBIN;
    private int replenishDelayMillis = 1000;
    /**
     * 预留二期能力。第一期不允许同一冗余组跨端口分散，避免远端重复转发。
     */
    private boolean spreadRedundantCopies;

    public void setHopCount(int hopCount) {
        this.hopCount = (byte) Math.max(1, Math.min(MAX_HOP_COUNT, hopCount));
        if (minActiveHops > this.hopCount) {
            minActiveHops = this.hopCount;
        }
    }

    public void setMinActiveHops(int minActiveHops) {
        this.minActiveHops = (byte) Math.max(1, Math.min(hopCount, minActiveHops));
    }

    public void setMode(UdpPortHoppingMode mode) {
        this.mode = mode != null ? mode : UdpPortHoppingMode.ROUND_ROBIN;
    }

    public void setReplenishDelayMillis(int replenishDelayMillis) {
        this.replenishDelayMillis = Math.max(0, replenishDelayMillis);
    }
}
