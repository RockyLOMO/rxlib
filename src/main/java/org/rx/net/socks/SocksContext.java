package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.Func;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SocksContext {
    //common
    private static final AttributeKey<SocksProxyServer> SERVER = AttributeKey.valueOf("SERVER");
    private static final AttributeKey<UnresolvedEndpoint> REAL_DESTINATION = AttributeKey.valueOf("REAL_DESTINATION");
    //udp
    private static final AttributeKey<InetSocketAddress> UDP_SOURCE = AttributeKey.valueOf("UDP_SOURCE");
    private static final AttributeKey<Channel> OUTBOUND = AttributeKey.valueOf("OUTBOUND");
    private static final AttributeKey<ConcurrentLinkedQueue<Object>> PENDING_QUEUE = AttributeKey.valueOf("PENDING_QUEUE");
    //ss
    private static final AttributeKey<ShadowsocksServer> SS_SERVER = AttributeKey.valueOf("SS_SERVER");

    public static InetSocketAddress udpSource(Channel channel) {
        return Objects.requireNonNull(channel.attr(UDP_SOURCE).get());
    }

    /**
     * call this method before bind & connect
     *
     * @param channel channel
     */
    public static void initPendingQueue(Channel channel, InetSocketAddress srcEp, UnresolvedEndpoint dstEp) {
        channel.attr(PENDING_QUEUE).set(new ConcurrentLinkedQueue<>());
        channel.attr(UDP_SOURCE).set(srcEp);
        channel.attr(REAL_DESTINATION).set(dstEp);
    }

    public static boolean addPendingPacket(Channel channel, Object packet) {
        ConcurrentLinkedQueue<Object> queue = channel.attr(PENDING_QUEUE).get();
        if (queue == null || channel.isActive()) {
            return false;
        }
        return queue.add(packet);
    }

    public static int flushPendingQueue(Channel channel) {
        ConcurrentLinkedQueue<Object> queue = channel.attr(PENDING_QUEUE).getAndRemove();
        if (queue == null) {
            return 0;
        }
        int size = queue.size();
        Sockets.writeAndFlush(channel, queue);
        return size;
    }


    public static SocksProxyServer server(Channel channel) {
        return Objects.requireNonNull(channel.attr(SERVER).get());
    }

    public static void server(Channel channel, SocksProxyServer server) {
        channel.attr(SERVER).set(server);
    }

    public static UnresolvedEndpoint realDestination(Channel channel) {
        return Objects.requireNonNull(channel.attr(REAL_DESTINATION).get());
    }

    public static void realDestination(Channel channel, UnresolvedEndpoint destination) {
        channel.attr(REAL_DESTINATION).set(destination);
    }

    public static Channel outbound(Channel channel) {
        return Objects.requireNonNull(channel.attr(OUTBOUND).get());
    }

    @SneakyThrows
    public static void outbound(@NonNull Channel channel, @NonNull Func<Channel> outboundFn) {
        synchronized (channel) {
            Channel val = channel.attr(OUTBOUND).get();
            if (val == null) {
                channel.attr(OUTBOUND).set(outboundFn.invoke());
            }
        }
    }


    public static ShadowsocksServer ssServer(Channel channel) {
        return Objects.requireNonNull(channel.attr(SS_SERVER).get());
    }

    public static void ssServer(Channel channel, ShadowsocksServer server) {
        channel.attr(SS_SERVER).set(server);
    }
}
