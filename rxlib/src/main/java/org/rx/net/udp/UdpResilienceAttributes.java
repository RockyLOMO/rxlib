package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * UDP Resilience peer 属性。
 */
public final class UdpResilienceAttributes {
    static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_RESILIENCE_PEERS =
            AttributeKey.valueOf("udpResiliencePeers");

    private UdpResilienceAttributes() {
    }

    public static ConcurrentMap<InetSocketAddress, Boolean> initResiliencePeers(Channel channel) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_RESILIENCE_PEERS).get();
        if (peers != null) {
            return peers;
        }
        ConcurrentMap<InetSocketAddress, Boolean> created = new ConcurrentHashMap<>();
        if (!channel.attr(ATTR_RESILIENCE_PEERS).compareAndSet(null, created)) {
            return channel.attr(ATTR_RESILIENCE_PEERS).get();
        }
        return created;
    }

    public static void addResiliencePeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        initResiliencePeers(channel).put(normalize(address), Boolean.TRUE);
    }

    public static void removeResiliencePeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_RESILIENCE_PEERS).get();
        if (peers != null) {
            peers.remove(normalize(address));
        }
    }

    public static boolean shouldApply(Channel channel, InetSocketAddress recipient) {
        return shouldApply(channel, recipient, false);
    }

    public static boolean shouldApply(Channel channel, InetSocketAddress recipient, boolean resilienceAll) {
        if (channel == null) {
            return resilienceAll;
        }
        if (recipient == null) {
            return resilienceAll;
        }
        InetSocketAddress normalized = normalize(recipient);
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_RESILIENCE_PEERS).get();
        if (peers != null) {
            return peers.containsKey(normalized);
        }
        return resilienceAll;
    }

    public static InetSocketAddress normalize(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        InetAddress inetAddress = address.getAddress();
        if (inetAddress != null) {
            return new InetSocketAddress(inetAddress, address.getPort());
        }
        String host = address.getHostString();
        if (host != null) {
            host = host.trim().toLowerCase(Locale.ROOT);
        }
        return Sockets.newUnresolvedEndpoint(host, address.getPort());
    }

    static int addressHash(InetSocketAddress address) {
        if (address == null) {
            return 0;
        }
        InetAddress inetAddress = address.getAddress();
        int hostHash = inetAddress != null ? inetAddress.hashCode()
                : (address.getHostString() != null ? address.getHostString().hashCode() : 0);
        return 31 * hostHash + address.getPort();
    }
}
