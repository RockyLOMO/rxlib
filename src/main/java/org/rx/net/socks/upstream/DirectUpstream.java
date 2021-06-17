package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import org.rx.net.support.UnresolvedEndpoint;

public class DirectUpstream extends Upstream {
    public DirectUpstream(UnresolvedEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        //do nth.
    }
}
