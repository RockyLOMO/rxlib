package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import org.rx.net.socks.support.UnresolvedEndpoint;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Upstream {
    @Getter
    protected UnresolvedEndpoint endpoint;
//    @Getter
//    protected final Queue<Object> pendingPackages = new ConcurrentLinkedQueue<>();

    public abstract void initChannel(SocketChannel channel);
}
