package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.net.SocketAddress;

@RequiredArgsConstructor
public class SessionClient implements Serializable {
    @Getter
    @Setter
    private String appId;
    protected final transient ChannelHandlerContext channel;

    public ChannelId getId() {
        return channel.channel().id();
    }

    public SocketAddress remoteAddress() {
        return channel.channel().remoteAddress();
    }
}
