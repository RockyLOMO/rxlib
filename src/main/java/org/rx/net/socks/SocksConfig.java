package org.rx.net.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class SocksConfig extends SocketConfig {
    private static final long serialVersionUID = 3526543718065617052L;
    public static final int UDP_BUF_SIZE = 64 * 1024;
    private final int listenPort;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = 60 * 60 * 24;
    private int writeTimeoutSeconds;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();

    private Set<InetAddress> whiteList() {
        Set<InetAddress> list = ConcurrentHashMap.newKeySet(1);
        list.add(Sockets.LOOPBACK_ADDRESS);
        return list;
    }
}
