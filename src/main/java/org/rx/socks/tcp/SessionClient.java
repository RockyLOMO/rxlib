package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.net.SocketAddress;

@RequiredArgsConstructor
public class SessionClient<T> {
    protected final ChannelHandlerContext ctx;
    @Getter
    @Setter
    private String groupId;
    @Getter
    private final T state;

    public ChannelId getId() {
        return ctx.channel().id();
    }

    public SocketAddress remoteAddress() {
        return ctx.channel().remoteAddress();
    }
}
