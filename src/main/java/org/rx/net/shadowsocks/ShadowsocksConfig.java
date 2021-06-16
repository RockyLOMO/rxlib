package org.rx.net.shadowsocks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;

import java.net.InetSocketAddress;

@Data
@EqualsAndHashCode(callSuper = true)
public class ShadowsocksConfig extends SocketConfig {
    private InetSocketAddress endpoint;
    private String method;
    private String password;
    private String obfs;
    private String obfsParam;
}
