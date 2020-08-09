package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;

public class DirectUpstream extends Upstream<SocketChannel> {
    @Override
    public void initChannel(SocketChannel channel) {
        // do nth.
    }
}
