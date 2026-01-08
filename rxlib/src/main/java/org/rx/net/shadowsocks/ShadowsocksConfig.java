package org.rx.net.shadowsocks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.SocketConfig;
import org.rx.net.socks.SocksConfig;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class ShadowsocksConfig extends SocketConfig {
    private static final long serialVersionUID = 9144214925505451056L;
    private final InetSocketAddress serverEndpoint;
    private final String method;
    private final String password;
    private int tcpTimeoutSeconds = SocksConfig.DEF_READ_TIMEOUT_SECONDS;
    private int udpTimeoutSeconds = SocksConfig.DEF_UDP_READ_TIMEOUT_SECONDS;
}
