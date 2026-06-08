package org.rx.net.udp;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * UDP Resilience 配置。默认值面向低延迟游戏 UDP：XOR 3:1 FEC，冗余倍率默认 1。
 */
@Getter
@Setter
@ToString
public class UdpResilienceConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean enabled = true;
    /**
     * true 表示安装 pipeline 后保护所有 DatagramPacket；false 表示只保护登记过的 peer。
     */
    private boolean resilienceAll = true;
    private boolean fecEnabled = true;
    private int fecDataShards = 3;
    private int fecParityShards = 1;
    private int fecFlushTimeoutMs = 5;
    private int staleGroupTimeoutMs = 300;
    private int maxResiliencePayload = 1200;
    private int maxPeersPerChannel = 4096;
    private int maxGroupsPerPeer = 32;
    private boolean redundantEnabled = true;
    private int redundantMultiplier = 1;
    private int redundantMaxMultiplier = 2;
    private int redundantIntervalMicros = 500;
    private int maxPendingDelayedCopies = 4096;
    private boolean dropTooLarge;
    private boolean dropOnLimit = true;
    private UdpResiliencePolicy policy = UdpResiliencePolicy.NONE;
    private UdpResilienceFlowIdResolver flowIdResolver = UdpResilienceFlowIdResolver.RECIPIENT;
    private transient UdpResilienceStats stats = new UdpResilienceStats();

    public static UdpResilienceConfig gameLowLatency() {
        return new UdpResilienceConfig();
    }

    public static UdpResilienceConfig light() {
        UdpResilienceConfig config = new UdpResilienceConfig();
        config.setFecDataShards(4);
        config.setRedundantMultiplier(1);
        return config;
    }

    public static UdpResilienceConfig extremeLoss() {
        UdpResilienceConfig config = new UdpResilienceConfig();
        config.setFecDataShards(3);
        config.setRedundantMultiplier(2);
        config.setRedundantIntervalMicros(700);
        return config;
    }

    public UdpResilienceStats stats() {
        if (stats == null) {
            stats = new UdpResilienceStats();
        }
        return stats;
    }

    public void setFecDataShards(int fecDataShards) {
        this.fecDataShards = Math.max(1, Math.min(32, fecDataShards));
    }

    public void setFecParityShards(int fecParityShards) {
        this.fecParityShards = Math.max(0, Math.min(1, fecParityShards));
    }

    public void setFecFlushTimeoutMs(int fecFlushTimeoutMs) {
        this.fecFlushTimeoutMs = Math.max(0, fecFlushTimeoutMs);
    }

    public void setStaleGroupTimeoutMs(int staleGroupTimeoutMs) {
        this.staleGroupTimeoutMs = Math.max(1, staleGroupTimeoutMs);
    }

    public void setMaxResiliencePayload(int maxResiliencePayload) {
        this.maxResiliencePayload = Math.max(1, Math.min(0xFFFF, maxResiliencePayload));
    }

    public void setMaxPeersPerChannel(int maxPeersPerChannel) {
        this.maxPeersPerChannel = Math.max(1, maxPeersPerChannel);
    }

    public void setMaxGroupsPerPeer(int maxGroupsPerPeer) {
        this.maxGroupsPerPeer = Math.max(1, maxGroupsPerPeer);
    }

    public void setRedundantMultiplier(int redundantMultiplier) {
        this.redundantMultiplier = Math.max(1, Math.min(5, redundantMultiplier));
    }

    public void setRedundantMaxMultiplier(int redundantMaxMultiplier) {
        this.redundantMaxMultiplier = Math.max(1, Math.min(5, redundantMaxMultiplier));
    }

    public void setRedundantIntervalMicros(int redundantIntervalMicros) {
        this.redundantIntervalMicros = Math.max(0, redundantIntervalMicros);
    }

    public void setMaxPendingDelayedCopies(int maxPendingDelayedCopies) {
        this.maxPendingDelayedCopies = Math.max(0, maxPendingDelayedCopies);
    }

    public void setPolicy(UdpResiliencePolicy policy) {
        this.policy = policy != null ? policy : UdpResiliencePolicy.NONE;
    }

    public void setFlowIdResolver(UdpResilienceFlowIdResolver flowIdResolver) {
        this.flowIdResolver = flowIdResolver != null ? flowIdResolver : UdpResilienceFlowIdResolver.RECIPIENT;
    }

    public void setStats(UdpResilienceStats stats) {
        this.stats = stats != null ? stats : new UdpResilienceStats();
    }
}
