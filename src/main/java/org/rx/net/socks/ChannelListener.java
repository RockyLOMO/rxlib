package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;

public interface ChannelListener {
    void active(ChannelHandlerContext ctx);

    void inactive(ChannelHandlerContext ctx);
}
