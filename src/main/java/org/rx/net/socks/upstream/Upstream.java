package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Upstream {
    @Getter
    protected SocketAddress address;
    @Getter
    protected final Queue<Object> pendingPackages = new ConcurrentLinkedQueue<>();

    public abstract void initChannel(SocketChannel channel);
}
