package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * UDP 压缩配置。
 * <p>
 * 初版仅支持单包无状态 LZ4 压缩，不支持字典压缩。
 */
@Getter
@Setter
@ToString
public class UdpCompressConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int DEFAULT_COMPRESSION_LEVEL = 0;
    public static final int DEFAULT_HIGH_COMPRESSION_LEVEL = 9;
    public static final int MAX_COMPRESSION_LEVEL = 17;

    /**
     * 是否启用 UDP 压缩。
     */
    private boolean enabled;
    /**
     * 压缩算法。当前仅支持 LZ4 fast。
     */
    private UdpCompressCodec codec = UdpCompressCodec.LZ4_FAST;
    /**
     * 小于该长度的包直接透传，避免在小包上浪费 CPU。
     */
    private int minPayloadBytes = 96;
    /**
     * 压缩后至少节省的字节数。
     */
    private int minSavingsBytes = 24;
    /**
     * 压缩后至少节省的比例。
     */
    private double minSavingsRatio = 0.12D;
    /**
     * LZ4 压缩等级。
     * 0 表示保持 fast compressor；1..17 表示使用 high compressor 对应等级。
     */
    private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;
    /**
     * 字典 id，0 表示无字典。当前实现阶段仅支持 0。
     */
    private short dictionaryId = 0;
    /**
     * 是否启用低收益旁路。
     */
    private boolean adaptiveBypass = true;
    /**
     * 旁路窗口秒数。
     */
    private int adaptiveBypassWindowSeconds = 30;

    public void setMinPayloadBytes(int minPayloadBytes) {
        this.minPayloadBytes = Math.max(1, minPayloadBytes);
    }

    public void setMinSavingsBytes(int minSavingsBytes) {
        this.minSavingsBytes = Math.max(0, minSavingsBytes);
    }

    public void setMinSavingsRatio(double minSavingsRatio) {
        if (Double.isNaN(minSavingsRatio) || Double.isInfinite(minSavingsRatio)) {
            this.minSavingsRatio = 0D;
            return;
        }
        this.minSavingsRatio = Math.max(0D, Math.min(1D, minSavingsRatio));
    }

    public void setCompressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel <= 0 ? DEFAULT_COMPRESSION_LEVEL : Math.min(MAX_COMPRESSION_LEVEL, compressionLevel);
    }

    public void setDictionaryId(int dictionaryId) {
        this.dictionaryId = (short) Math.max(0, Math.min(255, dictionaryId));
    }

    public void setAdaptiveBypassWindowSeconds(int adaptiveBypassWindowSeconds) {
        this.adaptiveBypassWindowSeconds = Math.max(1, adaptiveBypassWindowSeconds);
    }

    /**
     * 从 SocksConfig 复制 UDP 压缩配置到独立对象。
     */
    public static UdpCompressConfig fromSocksConfig(SocksConfig socksConfig) {
        UdpCompressConfig config = new UdpCompressConfig();
        config.setEnabled(socksConfig.isUdpCompressEnabled());
        config.setCodec(socksConfig.getUdpCompressCodec());
        config.setMinPayloadBytes(socksConfig.getUdpCompressMinPayloadBytes());
        config.setMinSavingsBytes(socksConfig.getUdpCompressMinSavingsBytes());
        config.setMinSavingsRatio(socksConfig.getUdpCompressMinSavingsRatio());
        config.setCompressionLevel(socksConfig.getUdpCompressCompressionLevel());
        config.setDictionaryId(socksConfig.getUdpCompressDictionaryId());
        config.setAdaptiveBypass(socksConfig.isUdpCompressAdaptiveBypass());
        config.setAdaptiveBypassWindowSeconds(socksConfig.getUdpCompressAdaptiveBypassWindowSeconds());
        return config;
    }

    /**
     * 将配置应用到 SocksConfig。
     */
    public void applyToSocksConfig(SocksConfig socksConfig) {
        socksConfig.setUdpCompressEnabled(enabled);
        socksConfig.setUdpCompressCodec(codec);
        socksConfig.setUdpCompressMinPayloadBytes(minPayloadBytes);
        socksConfig.setUdpCompressMinSavingsBytes(minSavingsBytes);
        socksConfig.setUdpCompressMinSavingsRatio(minSavingsRatio);
        socksConfig.setUdpCompressCompressionLevel(compressionLevel);
        socksConfig.setUdpCompressDictionaryId(dictionaryId);
        socksConfig.setUdpCompressAdaptiveBypass(adaptiveBypass);
        socksConfig.setUdpCompressAdaptiveBypassWindowSeconds(adaptiveBypassWindowSeconds);
    }
}
