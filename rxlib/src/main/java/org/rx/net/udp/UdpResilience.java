package org.rx.net.udp;

import io.netty.channel.ChannelPipeline;

/**
 * UDP Resilience pipeline 安装工具。
 */
public final class UdpResilience {
    public static final String DECODER_NAME = "udpResilienceDecoder";
    public static final String ENCODER_NAME = "udpResilienceEncoder";

    private UdpResilience() {
    }

    public static void install(ChannelPipeline pipeline, UdpResilienceConfig config) {
        UdpResilienceConfig actual = config != null ? config : UdpResilienceConfig.gameLowLatency();
        if (pipeline.get(DECODER_NAME) == null) {
            pipeline.addLast(DECODER_NAME, new UdpResilienceDecoder(actual));
        }
        if (pipeline.get(ENCODER_NAME) == null) {
            pipeline.addLast(ENCODER_NAME, new UdpResilienceEncoder(actual));
        }
    }
}
