package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.Objects;

public final class SocksContext {
    private static final AttributeKey<SocksProxyServer> SERVER = AttributeKey.valueOf("SERVER");
    private static final AttributeKey<UnresolvedEndpoint> REAL_DESTINATION = AttributeKey.valueOf("REAL_DESTINATION");
    private static final AttributeKey<InetSocketAddress> UDP_SOURCE = AttributeKey.valueOf("UDP_SOURCE");
    public static final AttributeKey<InetSocketAddress> UDP_OUT_ENDPOINT = AttributeKey.valueOf("_UDP_OUT_ENDPOINT");

    public static <T> T attr(Channel channel, AttributeKey<T> key) {
        return Objects.requireNonNull(channel.attr(key).get());
    }

    public static InetSocketAddress udpSource(Channel channel) {
        return Objects.requireNonNull(channel.attr(UDP_SOURCE).get());
    }

    public static void udpSource(Channel channel, InetSocketAddress source) {
        channel.attr(UDP_SOURCE).set(source);
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
}
