package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import org.rx.net.support.UnresolvedEndpoint;

public abstract class Upstream {
    @Getter
    protected UnresolvedEndpoint endpoint;

    public abstract void initChannel(SocketChannel channel);
}
