package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.support.UnresolvedEndpoint;

public class Upstream {
    @Getter
    protected volatile UnresolvedEndpoint destination;
    @Getter
    protected volatile AuthenticEndpoint proxyServer;

    public Upstream(@NonNull UnresolvedEndpoint dstEp) {
        destination = dstEp;
    }

    public void initChannel(Channel channel) {

    }
}
