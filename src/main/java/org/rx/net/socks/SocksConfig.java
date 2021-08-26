package org.rx.net.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class SocksConfig extends SocketConfig {
    private static final long serialVersionUID = 3526543718065617052L;
    public static final int BUF_SIZE_4K = 1024 * 4;
    private final int listenPort;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = 60 * 60 * 8;
    private int writeTimeoutSeconds;
    private int udpTimeoutSeconds = 60 * 4;
    private boolean enableUdp2raw;
    private List<InetSocketAddress> udp2rawServers;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();

    private Set<InetAddress> whiteList() {
        Set<InetAddress> list = ConcurrentHashMap.newKeySet(1);
        list.add(Sockets.LOOPBACK_ADDRESS);
        return list;
    }
}
