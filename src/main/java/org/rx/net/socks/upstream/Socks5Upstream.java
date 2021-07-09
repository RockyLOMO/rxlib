package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.SocksConfig;
import org.rx.net.TransportUtil;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.quietly;

public class Socks5Upstream extends Upstream {
    final SocksConfig config;
    @Getter
    final RandomList<AuthenticEndpoint> servers;
    final Map<AuthenticEndpoint, SocksSupport> supports;

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull AuthenticEndpoint... svrEps) {
        this(dstEp, config, new RandomList<>(Arrays.toList(svrEps)));
    }

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull RandomList<AuthenticEndpoint> svrEps) {
        endpoint = dstEp;
        this.config = config;
        this.servers = svrEps;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        if (servers.isEmpty()) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        TransportUtil.addBackendHandler(channel, config, getEndpoint().toSocketAddress());
        AuthenticEndpoint svrEp = servers.next();

        SocksSupport support = supports.get(svrEp);
        if (support != null
                && (SocksSupport.FAKE_IPS.contains(endpoint.getHost()) || !Sockets.isValidIp(endpoint.getHost()))) {
            String dstEpStr = endpoint.toString();
            App.logMetric(String.format("socks5[%s]", config.getListenPort()), Strings.EMPTY);
            SUID hash = SUID.compute(dstEpStr);
            Cache.getOrSet(hash, k -> quietly(() -> Tasks.run(() -> {
                support.fakeEndpoint(hash, dstEpStr);
                return true;
            }).get(8, TimeUnit.SECONDS)));
            endpoint = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomGet(SocksSupport.FAKE_PORT_OBFS));
        }

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
