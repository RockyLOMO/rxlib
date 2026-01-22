package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.SocketConfig;
import org.rx.net.support.UnresolvedEndpoint;

@Getter
public class Upstream {
    protected UnresolvedEndpoint destination;
    //Maybe frontend have a different configuration from backend
    protected SocketConfig config;

    public Upstream(UnresolvedEndpoint dstEp) {
        this(dstEp, null);
    }

    public Upstream(UnresolvedEndpoint dstEp, SocketConfig conf) {
        reuse(dstEp, conf);
    }

    public void reuse(@NonNull UnresolvedEndpoint dstEp, SocketConfig conf) {
        destination = dstEp;
        config = conf;
    }

    public void initChannel(Channel channel) {
    }
}
