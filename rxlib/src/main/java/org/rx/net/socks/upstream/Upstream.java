package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.SocketConfig;
import java.net.InetSocketAddress;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

@Getter
public class Upstream {
    protected InetSocketAddress destination;
    //Maybe frontend have a different configuration from backend
    protected SocketConfig config;

    public Upstream(InetSocketAddress dstEp) {
        this(dstEp, null);
    }

    public Upstream(InetSocketAddress dstEp, SocketConfig conf) {
        reuse(dstEp, conf);
    }

    public void reuse(@NonNull InetSocketAddress dstEp, SocketConfig conf) {
        destination = dstEp;
        config = conf;
    }

    public void initChannel(Channel channel) {
    }

    public CompletableFuture<Void> initChannelAsync(Channel channel) {
        initChannel(channel);
        return CompletableFuture.completedFuture(null);
    }

    public SocketAddress connectAddressHint() {
        return destination;
    }
}
