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
    final AuthenticEndpoint udpSocksServer;

    public SocksUdpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        udpSocksServer = next.getEndpoint();
    }

    @Override
    public void initChannel(Channel channel) {
        Sockets.addClientHandler(channel, config, udpSocksServer.getEndpoint());
    }
}
