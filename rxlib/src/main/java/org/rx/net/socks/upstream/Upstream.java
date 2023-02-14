package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.support.UnresolvedEndpoint;

@AllArgsConstructor
@Getter
public class Upstream {
    protected volatile UnresolvedEndpoint destination;
    protected volatile AuthenticEndpoint socksServer;

    public Upstream(@NonNull UnresolvedEndpoint dstEp) {
        destination = dstEp;
    }

    public void initChannel(Channel channel) {
    }
}
