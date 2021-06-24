package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.core.NQuery;
import org.rx.core.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksConfig;
import org.rx.net.TransportUtil;
import org.rx.net.support.UnresolvedEndpoint;

public class Socks5Upstream extends Upstream {
    final SocksConfig config;
    @Getter
    final RandomList<Socks5ProxyHandler> proxyHandlers = new RandomList<>();

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull AuthenticEndpoint... authEps) {
        endpoint = dstEp;
        this.config = config;
        proxyHandlers.addAll(NQuery.of(authEps).select(authEp -> {
            Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(authEp.getEndpoint(), authEp.getUsername(), authEp.getPassword());
            proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
            return proxyHandler;
        }).toList());
    }

    @Override
    public void initChannel(SocketChannel channel) {
        if (proxyHandlers.isEmpty()) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        TransportUtil.addBackendHandler(channel, config, getEndpoint().toSocketAddress());
        channel.pipeline().addLast(proxyHandlers.next());
    }
}
