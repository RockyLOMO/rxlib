package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import org.rx.net.Sockets;
import org.rx.net.udp.UdpCompressDecoder;
import org.rx.net.udp.UdpPeerAttributes;
import org.rx.net.udp.UdpRedundantDecoder;
import org.rx.net.udp.UdpResilienceAttributes;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

public final class UdpRelayAttributes {
    static final AttributeKey<Boolean> ATTR_CLIENT_LOCKED = AttributeKey.valueOf("udpClientLocked");
    static final AttributeKey<InetSocketAddress> ATTR_CLIENT_ORIGIN_ADDR = AttributeKey.valueOf("udpClientOriginAddr");
    static final AttributeKey<ConcurrentMap<InetSocketAddress, Boolean>> ATTR_REDUNDANT_PEERS =
            UdpPeerAttributes.ATTR_ENCODE_PEERS;
    static final AttributeKey<Boolean> ATTR_REDUNDANT_CLIENT_PEER = AttributeKey.valueOf("udpRedundantClientPeer");
    static final AttributeKey<InetSocketAddress> ATTR_REDUNDANT_CLIENT_ADDR = AttributeKey.valueOf("udpRedundantClientAddr");

    private UdpRelayAttributes() {
    }

    /** Older RSS peers may still send RDNT followed by UCMP. Never decode ordinary Internet payloads. */
    public static void installLegacyDecoders(Channel ch, String role, Predicate<InetSocketAddress> peer) {
        ChannelPipeline p = ch.pipeline();
        String first = role + "Rdnt";
        if (p.get(first) != null) {
            return;
        }
        Predicate<Object> accept = msg -> msg instanceof DatagramPacket && peer.test(((DatagramPacket) msg).sender());
        p.addAfter(Sockets.UDP_FINAL_EGRESS_GUARD, first, new UdpRedundantDecoder() {
            @Override public boolean acceptInboundMessage(Object msg) {
                return accept.test(msg);
            }
        });
        p.addAfter(first, role + "Ucmp", new UdpCompressDecoder() {
            @Override public boolean acceptInboundMessage(Object msg) {
                return accept.test(msg);
            }
        });
    }

    static void acceptLegacyClient(Channel ch, SocksConfig config) {
        if (config.isDailyUdp()) {
            installLegacyDecoders(ch, "legacyClient", sender -> {
                ConcurrentMap<InetSocketAddress, SocksContext> targets = ch.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).get();
                return (targets == null || !targets.containsKey(sender)) && config.isAllowed(sender.getAddress());
            });
        }
    }

    public static ConcurrentMap<InetSocketAddress, Boolean> initRedundantPeers(Channel channel) {
        return UdpPeerAttributes.initEncodePeers(channel);
    }

    public static void addRedundantPeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        UdpPeerAttributes.addEncodePeer(channel, address);
    }

    public static void addRedundantClientPeerIfChanged(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        InetSocketAddress normalized = normalize(address);
        InetSocketAddress current = channel.attr(ATTR_REDUNDANT_CLIENT_ADDR).get();
        if (normalized.equals(current)) {
            return;
        }
        UdpPeerAttributes.addEncodePeer(channel, normalized);
        channel.attr(ATTR_REDUNDANT_CLIENT_ADDR).set(normalized);
    }

    public static void removeRedundantPeer(Channel channel, InetSocketAddress address) {
        if (channel == null || address == null) {
            return;
        }
        UdpPeerAttributes.removeEncodePeer(channel, address);
    }

    public static boolean shouldEncode(Channel channel, InetSocketAddress recipient) {
        return UdpPeerAttributes.shouldEncode(channel, recipient);
    }

    public static boolean shouldTrackClientAsRedundantPeer(SocksConfig config) {
        return shouldTrackClientAsRedundantPeer(config, false);
    }

    public static boolean shouldTrackClientAsRedundantPeer(SocksConfig config, boolean udp2raw) {
        if (!UdpRedundantSupport.isConfigured(config)) {
            return false;
        }
        return udp2raw
                ? UdpRedundantSupport.allowUdp2rawResponse(config)
                : UdpRedundantSupport.allowSocksUdpResponse(config);
    }

    public static InetSocketAddress normalize(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        return UdpResilienceAttributes.normalize(address);
    }
}
