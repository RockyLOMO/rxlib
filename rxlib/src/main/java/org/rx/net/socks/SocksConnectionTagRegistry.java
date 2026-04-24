package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SocksConnectionTagRegistry {
    public static final String PARAM_NAME = "trafficUser";
    private static final ConcurrentMap<SocketAddress, String> TAGS = new ConcurrentHashMap<>();

    public static void bindOnActive(Channel channel, String tag) {
        if (channel == null || tag == null || tag.isEmpty()) {
            return;
        }
        if (register(channel, tag)) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        String handlerName = SocksConnectionTagRegistry.class.getSimpleName();
        if (pipeline.get(handlerName) != null) {
            return;
        }
        pipeline.addFirst(handlerName, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                register(ctx.channel(), tag);
                ctx.pipeline().remove(this);
                super.channelActive(ctx);
            }
        });
    }

    public static String resolve(Channel channel) {
        if (channel == null) {
            return null;
        }
        return TAGS.get(channel.remoteAddress());
    }

    private static boolean register(Channel channel, String tag) {
        SocketAddress localAddress = channel.localAddress();
        if (localAddress == null) {
            return false;
        }
        TAGS.put(localAddress, tag);
        channel.closeFuture().addListener(f -> TAGS.remove(localAddress, tag));
        return true;
    }
}
