package org.rx.net.shadowsocks.socks5;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;

public final class SocksServerInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(
//                new LoggingHandler(LogLevel.INFO),
                new SocksPortUnificationServerHandler(),
                SocksServerHandler.INSTANCE);
    }
}