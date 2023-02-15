package org.rx.net.shadowsocks;

import lombok.*;
import org.rx.net.SocketConfig;

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
    private int tcpTimeoutSeconds = 60 * 2;
    private int udpTimeoutSeconds = 60 * 10;
}
