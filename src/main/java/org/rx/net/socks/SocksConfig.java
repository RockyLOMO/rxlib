package org.rx.net.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.FlagsEnum;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class SocksConfig extends SocketConfig {
    public static final int DNS_PORT = 53;
    public static final byte[] DNS_KEY = "FREEDOM".getBytes(StandardCharsets.UTF_8);
    private final int listenPort;
    private final FlagsEnum<TransportFlags> transportFlags;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds;
    private int writeTimeoutSeconds;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();

    private Set<InetAddress> whiteList() {
        Set<InetAddress> list = ConcurrentHashMap.newKeySet(1);
        list.add(Sockets.LOOPBACK_ADDRESS);
        return list;
    }
}
