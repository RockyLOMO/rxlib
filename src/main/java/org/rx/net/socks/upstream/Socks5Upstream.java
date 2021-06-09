package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import lombok.NonNull;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SslUtil;

import java.net.InetSocketAddress;

public class Socks5Upstream extends Upstream {
    final Socks5ProxyHandler proxyHandler;
    final SocksConfig config;

    public Socks5Upstream(@NonNull SocksConfig config, AuthenticEndpoint authenticEndpoint) {
        this.config = config;
        setAddress(authenticEndpoint.getEndpoint());
        proxyHandler = new Socks5ProxyHandler(getAddress(), authenticEndpoint.getUsername(), authenticEndpoint.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
    }

    @Override
    public void initChannel(SocketChannel channel) {
        channel.pipeline().addFirst(HANDLER_NAME, proxyHandler);
        SslUtil.addBackendHandler(channel, config.getTransportFlags(), (InetSocketAddress) getAddress(), true);
    }
}
