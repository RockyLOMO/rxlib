package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import lombok.NonNull;
import org.rx.net.support.UnresolvedEndpoint;

public class DirectUpstream extends Upstream {
    public DirectUpstream(@NonNull UnresolvedEndpoint dstEp) {
        this.endpoint = dstEp;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        //do nth.
    }
}
