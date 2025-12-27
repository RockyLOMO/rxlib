package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.support.UnresolvedEndpoint;

@AllArgsConstructor
@Getter
public class Upstream {
    //Maybe frontend have a different configuration from backend
    protected SocketConfig config;
    protected volatile UnresolvedEndpoint destination;
    protected volatile AuthenticEndpoint socksServer;

    public Upstream(UnresolvedEndpoint dstEp) {
        this(null, dstEp);
    }

    public Upstream(SocketConfig conf, UnresolvedEndpoint dstEp) {
        reuse(conf, dstEp);
    }

    public void reuse(SocketConfig conf, @NonNull UnresolvedEndpoint dstEp) {
        config = conf;
        destination = dstEp;
    }

    public void initChannel(Channel channel) {
    }
}
