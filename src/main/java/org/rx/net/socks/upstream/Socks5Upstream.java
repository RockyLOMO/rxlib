package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.NonNull;
import org.rx.core.NQuery;
import org.rx.core.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SslUtil;
import org.rx.net.support.UnresolvedEndpoint;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class Socks5Upstream extends Upstream {
    final SocksConfig config;
    final List<Socks5ProxyHandler> proxyHandlers = new CopyOnWriteArrayList<>();

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

        int i = ThreadLocalRandom.current().nextInt(0, proxyHandlers.size());
        channel.pipeline().addFirst("proxy", proxyHandlers.get(i));
        SslUtil.addBackendHandler(channel, config.getTransportFlags(), getEndpoint().toSocketAddress(), true);
    }
}
