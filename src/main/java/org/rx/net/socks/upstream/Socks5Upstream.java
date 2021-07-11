package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;
import org.rx.net.TransportUtil;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.util.concurrent.TimeUnit;

import static org.rx.core.App.quietly;

public class Socks5Upstream extends Upstream {
    final SocksConfig config;
    final RandomList<UpstreamSupport> servers;

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull AuthenticEndpoint... svrEps) {
        this(dstEp, config, new RandomList<>(NQuery.of(svrEps).select(p -> new UpstreamSupport(p, null)).toList()));
    }

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config,
                          @NonNull RandomList<UpstreamSupport> servers) {
        endpoint = dstEp;
        this.config = config;
        this.servers = servers;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        if (servers.isEmpty()) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        TransportUtil.addBackendHandler(channel, config, getEndpoint().toSocketAddress());

        UpstreamSupport next = servers.next();
        AuthenticEndpoint svrEp = next.getEndpoint();
        SocksSupport support = next.getSupport();
        if (support != null
                && (SocksSupport.FAKE_IPS.contains(endpoint.getHost()) || SocksSupport.FAKE_PORTS.contains(endpoint.getPort())
                || !Sockets.isValidIp(endpoint.getHost()))) {
            String dstEpStr = endpoint.toString();
            SUID hash = SUID.compute(dstEpStr);
            //先变更
            endpoint = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomGet(SocksSupport.FAKE_PORT_OBFS));
            Cache.getOrSet(hash, k -> quietly(() -> Tasks.run(() -> {
                App.logMetric(String.format("socks5[%s]", config.getListenPort()), dstEpStr);
                support.fakeEndpoint(hash, dstEpStr);
                return true;
            }).get(SocksSupport.ASYNC_TIMEOUT, TimeUnit.MILLISECONDS)));
        }

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
