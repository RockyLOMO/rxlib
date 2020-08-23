package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.rx.net.Sockets;

import java.net.SocketAddress;

public class Socks5Upstream extends Upstream {
    private final Socks5ProxyHandler proxyHandler;

    public Socks5Upstream(String endpoint) {
        this(Sockets.parseEndpoint(endpoint), null, null);
    }

    public Socks5Upstream(SocketAddress address, String username, String password) {
        setAddress(address);
        proxyHandler = new Socks5ProxyHandler(getAddress(), username, password);
    }

    @Override
    public void initChannel(SocketChannel channel) {
        channel.pipeline().addFirst(HANDLER_NAME, proxyHandler);
    }
}
