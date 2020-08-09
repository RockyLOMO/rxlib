package org.rx.net.socks;

import io.netty.channel.socket.SocketChannel;
import org.rx.net.socks.upstream.Upstream;

public interface Authenticator {
    boolean auth(String username, String password);

    Upstream<SocketChannel> upstream(String username);
}
