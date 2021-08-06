package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.TransportUtil;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

public class ProxyUdpUpstream extends UdpUpstream {
    final RandomList<UpstreamSupport> servers;

    public ProxyUdpUpstream(@NonNull UnresolvedEndpoint dstEp, @NonNull RandomList<UpstreamSupport> servers) {
        super(dstEp);
        this.servers = servers;
    }

    @Override
    public void initChannel(Channel channel) {
        super.initChannel(channel);

        UpstreamSupport next = servers.next();
        AuthenticEndpoint svrEp = proxyServer = next.getEndpoint();

        SocksProxyServer server = SocksContext.attr(channel, SocksContext.SERVER);
        TransportUtil.addBackendHandler(channel, server.getConfig(), svrEp.getEndpoint());
    }
}
