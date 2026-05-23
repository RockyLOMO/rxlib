package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * UDP 通用 peer 集合，用于压缩、冗余等只应作用于自有链路对端的能力。
 */
public final class UdpPeerAttributes {
    public static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_ENCODE_PEERS =
            AttributeKey.valueOf("udpEncodePeers");

    private UdpPeerAttributes() {
    }

    public static ConcurrentMap<InetSocketAddress, Boolean> initEncodePeers(Channel channel) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_ENCODE_PEERS).get();
        if (peers != null) {
            return peers;
        }
        ConcurrentMap<InetSocketAddress, Boolean> created = new ConcurrentHashMap<>();
        if (!channel.attr(ATTR_ENCODE_PEERS).compareAndSet(null, created)) {
            return channel.attr(ATTR_ENCODE_PEERS).get();
        }
        return created;
    }

    public static void addEncodePeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        initEncodePeers(channel).put(UdpResilienceAttributes.normalize(address), Boolean.TRUE);
    }

    public static void removeEncodePeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_ENCODE_PEERS).get();
        if (peers != null) {
            peers.remove(UdpResilienceAttributes.normalize(address));
        }
    }

    public static boolean shouldEncode(Channel channel, InetSocketAddress recipient) {
        ConcurrentMap<InetSocketAddress, Boolean> peers = channel.attr(ATTR_ENCODE_PEERS).get();
        return peers != null && recipient != null && peers.containsKey(UdpResilienceAttributes.normalize(recipient));
    }
}
