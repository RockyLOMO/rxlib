package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

public class SocksUdpUpstream extends Upstream {
    @Getter
    AuthenticEndpoint udpSocksServer;

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
