package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.util.function.Func;

public class SocksUdpUpstream extends Upstream {
    private Func<UpstreamSupport> router;

    public SocksUdpUpstream(@NonNull SocksConfig config, UnresolvedEndpoint dstEp, @NonNull Func<UpstreamSupport> router) {
        super(config, dstEp);
        this.router = router;
    }

    public void reuse(@NonNull SocksConfig config, UnresolvedEndpoint dstEp, @NonNull Func<UpstreamSupport> router) {
        super.config = config;
        super.destination = dstEp;
        this.router = router;
    }

    @SneakyThrows
    @Override
    public void initChannel(Channel channel) {
        UpstreamSupport next = router.invoke();
        if (next == null) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        AuthenticEndpoint svrEp = udpSocksServer = next.getEndpoint();
        Sockets.addClientHandler(channel, config, svrEp.getEndpoint());
    }
}
