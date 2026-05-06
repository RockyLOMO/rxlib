package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import org.rx.net.SocketConfig;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Fixed UDP client/tunnel upstream.
 *
 * <p>The destination remains the real target while packets are sent to a
 * configured UDP client address. Handlers keep the SOCKS5 UDP header on this
 * path so the UDP client can forward the datagram with destination metadata.
 */
public final class UdpClientUpstream extends Upstream {
    private final InetSocketAddress udpClientAddress;

    public UdpClientUpstream(UnresolvedEndpoint dstEp, SocketConfig config,
                             @NonNull InetSocketAddress udpClientAddress) {
        super(dstEp, config);
        this.udpClientAddress = udpClientAddress;
    }

    public InetSocketAddress getUdpClientAddress(Channel channel) {
        return udpClientAddress;
    }

    public Object clientAffinity() {
        return udpClientAddress;
    }

    public boolean ownsUdpClientAddress(InetSocketAddress sender) {
        return sameAddress(udpClientAddress, sender);
    }

    @Override
    public SocketAddress connectAddressHint() {
        return udpClientAddress;
    }

    private static boolean sameAddress(InetSocketAddress a, InetSocketAddress b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getPort() != b.getPort()) {
            return false;
        }
        if (a.getAddress() != null && b.getAddress() != null) {
            return a.getAddress().equals(b.getAddress());
        }
        return a.getHostString().equalsIgnoreCase(b.getHostString());
    }
}
