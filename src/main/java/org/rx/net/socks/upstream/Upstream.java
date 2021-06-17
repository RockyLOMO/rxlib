package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

public abstract class Upstream {
    public static final BiFunc<UnresolvedEndpoint, Upstream> DIRECT_ROUTER = DirectUpstream::new;

    @Getter
    protected UnresolvedEndpoint endpoint;

    public abstract void initChannel(SocketChannel channel);
}
