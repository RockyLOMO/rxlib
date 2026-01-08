package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.cache.DiskCache;
import org.rx.net.SocketConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

//@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksConfig extends SocketConfig {
    public static final int DEF_READ_TIMEOUT_SECONDS = 60 * 4;
    public static final int DEF_UDP_READ_TIMEOUT_SECONDS = 60 * 30;

    private static final long serialVersionUID = 3526543718065617052L;
    private int listenPort;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = DEF_READ_TIMEOUT_SECONDS;
    private int writeTimeoutSeconds;
    private int udpReadTimeoutSeconds = DEF_UDP_READ_TIMEOUT_SECONDS;
    private int udpWriteTimeoutSeconds;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();
    private boolean enableUdp2raw;
    private InetSocketAddress udp2rawClient;

    private Set<InetAddress> whiteList() {
        return DiskCache.DEFAULT.asSet();
    }

    public SocksConfig(int listenPort) {
        this.listenPort = listenPort;
    }
}
