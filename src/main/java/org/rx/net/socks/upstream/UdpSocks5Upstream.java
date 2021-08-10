package org.rx.net.socks.upstream;

import io.netty.channel.*;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.TransportUtil;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

public class UdpSocks5Upstream extends UdpUpstream {
    final SocksConfig config;
    final RandomList<UpstreamSupport> servers;

    public UdpSocks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config,
                             @NonNull RandomList<UpstreamSupport> servers) {
        super(dstEp);
        this.config = config;
        this.servers = servers;
    }

    @Override
    public void initChannel(Channel channel) {
        super.initChannel(channel);

        UpstreamSupport next = servers.next();
        AuthenticEndpoint svrEp = socksServer = next.getEndpoint();
        TransportUtil.addBackendHandler(channel, config, svrEp.getEndpoint());
    }
}
