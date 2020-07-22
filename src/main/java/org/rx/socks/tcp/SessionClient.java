package org.rx.socks.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.bean.DateTime;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
public class SessionClient<T> {
    protected final Channel channel;
    @Getter
    private final DateTime connectedTime = DateTime.now();
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private volatile String groupId;
    @Getter
    private final T state;

    public ChannelId getId() {
        return channel.id();
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }
}
