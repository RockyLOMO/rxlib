package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class UdpRelayAttributes {
    static final AttributeKey<Boolean> ATTR_CLIENT_LOCKED = AttributeKey.valueOf("udpClientLocked");
    static final AttributeKey<InetSocketAddress> ATTR_CLIENT_ORIGIN_ADDR = AttributeKey.valueOf("udpClientOriginAddr");
    static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_REDUNDANT_PEERS = AttributeKey.valueOf("udpRedundantPeers");
    static final AttributeKey<Boolean> ATTR_REDUNDANT_CLIENT_PEER = AttributeKey.valueOf("udpRedundantClientPeer");

    private UdpRelayAttributes() {
    }

    public static ConcurrentMap<InetSocketAddress, Boolean> initRedundantPeers(Channel channel) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_REDUNDANT_PEERS).get();
        if (peers != null) {
            return peers;
        }
        ConcurrentMap<InetSocketAddress, Boolean> created = new ConcurrentHashMap<>();
        if (!channel.attr(ATTR_REDUNDANT_PEERS).compareAndSet(null, created)) {
            return channel.attr(ATTR_REDUNDANT_PEERS).get();
        }
        return created;
    }

    public static void addRedundantPeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        initRedundantPeers(channel).put(normalize(address), Boolean.TRUE);
    }

    public static boolean shouldEncode(Channel channel, InetSocketAddress recipient) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_REDUNDANT_PEERS).get();
        if (peers == null) {
            return true;
        }
        return recipient != null && peers.containsKey(normalize(recipient));
    }

    static InetSocketAddress normalize(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        if (address.getAddress() != null) {
            return new InetSocketAddress(address.getAddress(), address.getPort());
        }
        return InetSocketAddress.createUnresolved(address.getHostString(), address.getPort());
    }
}
