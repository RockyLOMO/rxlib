package org.rx.net.socks.upstream;

import io.netty.channel.socket.SocketChannel;
import org.rx.net.AESHandler;

public class DirectUpstream extends Upstream {
    @Override
    public void initChannel(SocketChannel channel) {
        AESHandler aesHandler = channel.pipeline().get(AESHandler.class);
        if (aesHandler != null) {
            aesHandler.setSkipDecode(true);
        }
    }
}
