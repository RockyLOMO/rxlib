package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@RequiredArgsConstructor
public class SessionClient implements Serializable {
    protected final transient ChannelHandlerContext channel;

    public ChannelId getId() {
        return channel.channel().id();
    }
}
