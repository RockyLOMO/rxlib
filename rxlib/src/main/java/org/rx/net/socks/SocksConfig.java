package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksConfig extends SocketConfig {
    private static final long serialVersionUID = 3526543718065617052L;
    private final int listenPort;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = 60 * 4;
    private int writeTimeoutSeconds;
    private int udpAssociateMaxLifeSeconds = 60 * 60 * 12;
    private int udpReadTimeoutSeconds = 60 * 20;
    private int udpWriteTimeoutSeconds;
    private boolean enableUdp2raw;
    private List<InetSocketAddress> udp2rawServers;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();

    private Set<InetAddress> whiteList() {
        Set<InetAddress> list = ConcurrentHashMap.newKeySet(1);
        list.add(Sockets.getLoopbackAddress());
        return list;
    }
}
