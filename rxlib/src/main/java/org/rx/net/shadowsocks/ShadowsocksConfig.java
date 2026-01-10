package org.rx.net.shadowsocks;

import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.SocketConfig;
import org.rx.net.shadowsocks.encryption.ICrypto;
import org.rx.net.socks.SocksConfig;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class ShadowsocksConfig extends SocketConfig {
    public static final AttributeKey<ShadowsocksServer> SVR = AttributeKey.valueOf("ssSvr");
    public static final AttributeKey<ICrypto> CIPHER = AttributeKey.valueOf("CIPHER");
    public static final AttributeKey<InetSocketAddress> REMOTE_DEST = AttributeKey.valueOf("REMOTE_DEST");
    public static final AttributeKey<InetSocketAddress> REMOTE_SRC = AttributeKey.valueOf("REMOTE_SRC");

    private static final long serialVersionUID = 9144214925505451056L;
    private final InetSocketAddress serverEndpoint;
    private final String method;
    private final String password;
    private int udpTimeoutSeconds = SocksConfig.DEF_UDP_READ_TIMEOUT_SECONDS;
}
