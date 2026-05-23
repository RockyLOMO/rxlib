package org.rx.net.udp;

import io.netty.channel.ChannelPipeline;

/**
 * UDP Protect pipeline 安装工具。
 */
public final class UdpProtect {
    public static final String DECODER_NAME = "udpProtectDecoder";
    public static final String ENCODER_NAME = "udpProtectEncoder";

    private UdpProtect() {
    }

    public static void install(ChannelPipeline pipeline, UdpProtectConfig config) {
        UdpProtectConfig actual = config != null ? config : UdpProtectConfig.gameLowLatency();
        if (pipeline.get(DECODER_NAME) == null) {
            pipeline.addLast(DECODER_NAME, new UdpProtectDecoder(actual));
        }
        if (pipeline.get(ENCODER_NAME) == null) {
            pipeline.addLast(ENCODER_NAME, new UdpProtectEncoder(actual));
        }
    }
}
