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
public final class TcpWarmPoolKey implements Serializable {
    private static final long serialVersionUID = 5233525606849670764L;

    public static TcpWarmPoolKey from(AuthenticEndpoint endpoint, SocketConfig config, String reactorName) {
        String addr = Sockets.toString(endpoint.getEndpoint());
        long cipherKeyHash = config.getCipherKey() == null ? 0L : CodecUtil.hash64(config.getCipherKey());
        return new TcpWarmPoolKey(endpoint.getParameters().get(SocksConnectionTagRegistry.PARAM_NAME), addr, endpoint.getUsername(), endpoint.getPassword(),
                reactorName, config.getConnectTimeoutMillis(), config.getTransportFlags().getValue(), config.getCipher(), cipherKeyHash,
                config instanceof SocksConfig && ((SocksConfig) config).isAdaptiveTcp(),
                config instanceof SocksConfig ? ((SocksConfig) config).getAdaptiveProfile() : 0, config.getTcpCompressionLevel());
    }

    private final String trafficUser;
    private final String endpointText;
    private final String username;
    private final String password;
    private final String reactorName;
    private final int connectTimeoutMillis;
    private final int transportFlagsBits;
    private final short cipher;
    private final long cipherKeyHash;
    private final boolean adaptiveTcp;
    private final int adaptiveProfile;
    private final int tcpCompressionLevel;

    public TcpWarmPoolKey(String trafficUser, String endpointText, String username, String password,
                          String reactorName, int connectTimeoutMillis, int transportFlagsBits, short cipher,
                          long cipherKeyHash, boolean adaptiveTcp) {
        this(trafficUser, endpointText, username, password, reactorName, connectTimeoutMillis, transportFlagsBits,
                cipher, cipherKeyHash, adaptiveTcp, 0, -1);
    }

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
