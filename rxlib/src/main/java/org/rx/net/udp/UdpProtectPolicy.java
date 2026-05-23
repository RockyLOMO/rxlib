package org.rx.net.udp;

import lombok.Getter;
import lombok.ToString;

/**
 * 单次发送保护策略。第一版只暴露 DATA/PARITY 的冗余倍率。
 */
@Getter
@ToString
public final class UdpProtectPolicy {
    public static final UdpProtectPolicy NONE = new UdpProtectPolicy(1, 1);

    private final int dataMultiplier;
    private final int parityMultiplier;

    public UdpProtectPolicy(int dataMultiplier, int parityMultiplier) {
        this.dataMultiplier = clamp(dataMultiplier);
        this.parityMultiplier = clamp(parityMultiplier);
    }

    public static UdpProtectPolicy of(int dataMultiplier, int parityMultiplier) {
        return new UdpProtectPolicy(dataMultiplier, parityMultiplier);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }
}
