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
    public SocksUdpUpstream(@NonNull SocksConfig config, UnresolvedEndpoint dstEp, @NonNull UpstreamSupport next) {
        super(config, dstEp);
        udpSocksServer = next.getEndpoint();
    }

    public void reuse(@NonNull SocksConfig config, UnresolvedEndpoint dstEp, @NonNull UpstreamSupport next) {
        super.config = config;
        super.destination = dstEp;
        udpSocksServer = next.getEndpoint();
    }

    @Override
    public void initChannel(Channel channel) {
        Sockets.addClientHandler(channel, config, udpSocksServer.getEndpoint());
    }
}
