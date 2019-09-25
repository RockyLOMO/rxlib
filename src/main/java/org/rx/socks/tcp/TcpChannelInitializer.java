package org.rx.socks.tcp;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Arrays;

import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class TcpChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final TcpConfig config;
    private final Function<SocketChannel, SslHandler> sslHandlerSupplier;

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        if (sslHandlerSupplier != null) {
            pipeline.addLast(sslHandlerSupplier.apply(channel));
        }
        if (config.isEnableCompress()) {
            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }

        ChannelHandler[] handlers = config.getHandlersSupplier().get();
        if (Arrays.isEmpty(handlers)) {
            log.warn("Empty channel handlers");
            return;
        }
        pipeline.addLast(handlers);
    }
}
