package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SslUtil;
import org.rx.net.socks.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class Socks5Upstream extends Upstream {
    final SocksConfig config;
    final Socks5ProxyHandler proxyHandler;

    public Socks5Upstream(@NonNull UnresolvedEndpoint destAddr, @NonNull SocksConfig config, @NonNull AuthenticEndpoint authEp) {
        address = destAddr;
        this.config = config;
        proxyHandler = new Socks5ProxyHandler(authEp.getEndpoint(), authEp.getUsername(), authEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
    }

    @Override
    public void initChannel(SocketChannel channel) {
        channel.pipeline().addFirst("proxy", proxyHandler);
        SslUtil.addBackendHandler(channel, config.getTransportFlags(), getAddress().toSocketAddress(), true);
    }
}
