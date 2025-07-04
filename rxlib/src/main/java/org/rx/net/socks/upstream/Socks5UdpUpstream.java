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

public class Socks5UdpUpstream extends Upstream {
    final SocksConfig config;
    final Func<UpstreamSupport> router;

    public Socks5UdpUpstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull Func<UpstreamSupport> router) {
        super(dstEp);
        this.config = config;
        this.router = router;
    }

    @SneakyThrows
    @Override
    public void initChannel(Channel channel) {
        UpstreamSupport next = router.invoke();
        if (next == null) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        AuthenticEndpoint svrEp = socksServer = next.getEndpoint();
        Sockets.addClientHandler(channel, config, svrEp.getEndpoint());
    }
}
