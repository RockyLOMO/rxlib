package org.rx.socks.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
public class SessionClient<T> {
    protected final Channel channel;
    @Getter
    @Setter
    private String groupId;
    @Getter
    private final T state;

    public ChannelId getId() {
        return channel.id();
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }
}
