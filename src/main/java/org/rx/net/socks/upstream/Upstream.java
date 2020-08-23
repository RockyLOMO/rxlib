package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.net.SocketAddress;

public abstract class Upstream {
    public static final String HANDLER_NAME = "proxy";
    @Getter
    @Setter(AccessLevel.PROTECTED)
    private SocketAddress address;

    public abstract void initChannel(SocketChannel channel);
}
