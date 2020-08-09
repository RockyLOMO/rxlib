package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.rx.net.Sockets;

import java.net.SocketAddress;

public class Socks5Upstream extends Upstream<SocketChannel> {
    public Socks5Upstream(String endpoint) {
        this(Sockets.parseEndpoint(endpoint));
    }

    public Socks5Upstream(SocketAddress address) {
        setAddress(address);
    }

    @Override
    public void initChannel(SocketChannel channel) {
        channel.pipeline().addFirst(HANDLER_NAME, new Socks5ProxyHandler(getAddress()));
    }
}
