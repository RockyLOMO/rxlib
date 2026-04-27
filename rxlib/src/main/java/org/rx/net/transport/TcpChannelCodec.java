package org.rx.net.transport;

import io.netty.channel.ChannelPipeline;

import java.io.Serializable;

public interface TcpChannelCodec extends Serializable {
    void install(ChannelPipeline pipeline);
}
