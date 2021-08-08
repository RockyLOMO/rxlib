package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import static org.rx.core.Tasks.awaitQuietly;

public class Socks5Upstream extends Upstream {
    final RandomList<UpstreamSupport> servers;

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull AuthenticEndpoint... svrEps) {
        this(dstEp, new RandomList<>(NQuery.of(svrEps).select(p -> new UpstreamSupport(p, null)).toList()));
    }

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull RandomList<UpstreamSupport> servers) {
        super(dstEp);
        this.servers = servers;
    }

    @Override
    public void initChannel(Channel channel) {
        if (servers.isEmpty()) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        UpstreamSupport next = servers.next();
        AuthenticEndpoint svrEp = next.getEndpoint();
        SocksSupport support = next.getSupport();

        SocksProxyServer server = SocksContext.server(channel);
        TransportUtil.addBackendHandler(channel, server.getConfig(), svrEp.getEndpoint());

        if (support != null
                && (SocksSupport.FAKE_IPS.contains(destination.getHost()) || SocksSupport.FAKE_PORTS.contains(destination.getPort())
                || !Sockets.isValidIp(destination.getHost()))) {
            String dstEpStr = destination.toString();
            SUID hash = SUID.compute(dstEpStr);
            //先变更
            destination = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomGet(SocksSupport.FAKE_PORT_OBFS));
            Cache.getOrSet(hash, k -> awaitQuietly(() -> {
                App.logMetric(String.format("socks5[%s]", server.getConfig().getListenPort()), dstEpStr);
                support.fakeEndpoint(hash, dstEpStr);
                return true;
            }, SocksSupport.ASYNC_TIMEOUT));
        }

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(server.getConfig().getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
