package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * UDP Protect peer 属性。兼容读取历史 udpRedundantPeers，便于旧 SOCKS 链路平滑迁移。
 */
public final class UdpProtectAttributes {
    static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_PROTECTED_PEERS =
            AttributeKey.valueOf("udpProtectedPeers");
    @SuppressWarnings("unchecked")
    private static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_LEGACY_REDUNDANT_PEERS =
            (AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>>) (AttributeKey<?>) AttributeKey.valueOf("udpRedundantPeers");

    private UdpProtectAttributes() {
    }

    public static ConcurrentMap<InetSocketAddress, Boolean> initProtectedPeers(Channel channel) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_PROTECTED_PEERS).get();
        if (peers != null) {
            return peers;
        }
        ConcurrentMap<InetSocketAddress, Boolean> created = new ConcurrentHashMap<>();
        if (!channel.attr(ATTR_PROTECTED_PEERS).compareAndSet(null, created)) {
            return channel.attr(ATTR_PROTECTED_PEERS).get();
        }
        return created;
    }

    public static void addProtectedPeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        initProtectedPeers(channel).put(normalize(address), Boolean.TRUE);
    }

    public static void removeProtectedPeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_PROTECTED_PEERS).get();
        if (peers != null) {
            peers.remove(normalize(address));
        }
    }

    public static boolean shouldProtect(Channel channel, InetSocketAddress recipient) {
        return shouldProtect(channel, recipient, false);
    }

    public static boolean shouldProtect(Channel channel, InetSocketAddress recipient, boolean protectAll) {
        if (channel == null) {
            return protectAll;
        }
        if (recipient == null) {
            return protectAll;
        }
        InetSocketAddress normalized = normalize(recipient);
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_PROTECTED_PEERS).get();
        if (peers != null) {
            return peers.containsKey(normalized);
        }
        ConcurrentMap<InetSocketAddress, Boolean> legacyPeers = channel.attr(ATTR_LEGACY_REDUNDANT_PEERS).get();
        if (legacyPeers != null) {
            return legacyPeers.containsKey(normalized);
        }
        return protectAll;
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
        return InetSocketAddress.createUnresolved(host, address.getPort());
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
