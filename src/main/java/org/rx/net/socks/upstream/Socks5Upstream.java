package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.rx.net.AuthenticEndpoint;

public class Socks5Upstream extends Upstream {
    private final Socks5ProxyHandler proxyHandler;

    public Socks5Upstream(AuthenticEndpoint authenticEndpoint) {
        setAddress(authenticEndpoint.getEndpoint());
        proxyHandler = new Socks5ProxyHandler(getAddress(), authenticEndpoint.getUsername(), authenticEndpoint.getPassword());
    }

    @Override
    public void initChannel(SocketChannel channel) {
        channel.pipeline().addFirst(HANDLER_NAME, proxyHandler);
    }
}
