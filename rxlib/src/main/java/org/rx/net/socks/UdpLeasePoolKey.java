package org.rx.net.socks;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.codec.CodecUtil;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class UdpLeasePoolKey implements Serializable {
    private static final long serialVersionUID = 5165690324203544574L;

    public static UdpLeasePoolKey from(AuthenticEndpoint endpoint, SocketConfig config, String reactorName) {
        InetSocketAddress addr = endpoint.getEndpoint();
        long cipherKeyHash = config.getCipherKey() == null ? 0L : CodecUtil.hash64(config.getCipherKey());
        return new UdpLeasePoolKey(addr.getHostString(), addr.getPort(), endpoint.getUsername(), endpoint.getPassword(),
                reactorName, config.getConnectTimeoutMillis(), config.getTransportFlags().getValue(), config.getCipher(), cipherKeyHash);
    }

    private final String hostString;
    private final int port;
    private final String username;
    private final String password;
    private final String reactorName;
    private final int connectTimeoutMillis;
    private final int transportFlagsBits;
    private final short cipher;
    private final long cipherKeyHash;

    public boolean isSameEndpoint(AuthenticEndpoint endpoint) {
        InetSocketAddress addr = endpoint.getEndpoint();
        return port == addr.getPort()
                && hostString.equals(addr.getHostString())
                && eq(username, endpoint.getUsername())
                && eq(password, endpoint.getPassword());
    }

    private static boolean eq(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }
}
