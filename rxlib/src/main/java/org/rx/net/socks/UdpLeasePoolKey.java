package org.rx.net.socks;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.codec.CodecUtil;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.io.Serializable;

@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class UdpLeasePoolKey implements Serializable {
    private static final long serialVersionUID = 5165690324203544574L;

    public static UdpLeasePoolKey from(AuthenticEndpoint endpoint, SocketConfig config, String reactorName) {
        String addr = Sockets.toString(endpoint.getEndpoint());
        long cipherKeyHash = config.getCipherKey() == null ? 0L : CodecUtil.hash64(config.getCipherKey());
        return new UdpLeasePoolKey(addr, endpoint.getUsername(), endpoint.getPassword(),
                reactorName, config.getConnectTimeoutMillis(), config.getTransportFlags().getValue(), config.getCipher(), cipherKeyHash);
    }

    private final String endpointText;
    private final String username;
    private final String password;
    private final String reactorName;
    private final int connectTimeoutMillis;
    private final int transportFlagsBits;
    private final short cipher;
    private final long cipherKeyHash;

    public boolean isSameEndpoint(AuthenticEndpoint endpoint) {
        return endpointText.equals(Sockets.toString(endpoint.getEndpoint()))
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
