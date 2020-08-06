package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;

public interface FlowLogger {
    void log(ChannelHandlerContext ctx);
}
