package org.rx.net.shadowsocks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.rx.net.SocketConfig;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class ShadowsocksConfig extends SocketConfig {
    private final InetSocketAddress serverEndpoint;
    private final String method;
    private final String password;
    private int idleTimeout = 60 * 4;
    private String obfs;
    private String obfsParam;
}
