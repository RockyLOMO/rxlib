package org.rx.net.tcp;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.tcp.packet.HandshakePacket;

import java.net.InetSocketAddress;

import static org.rx.core.Contract.CONFIG;

@Data
@RequiredArgsConstructor
public class TcpConfig {
    public static TcpClient client(boolean tryEpoll, InetSocketAddress serverEndpoint, String groupId) {
        return new TcpClient(packetConfig(tryEpoll, serverEndpoint), new HandshakePacket(groupId));
    }

    public static TcpServer server(boolean tryEpoll, int port) {
        return new TcpServer(packetConfig(tryEpoll, Sockets.getAnyEndpoint(port)));
    }

    private static TcpConfig packetConfig(boolean tryEpoll, InetSocketAddress endpoint) {
        TcpConfig config = new TcpConfig(tryEpoll);
        config.setEndpoint(endpoint);
        config.setEnableSsl(false);
        config.setEnableCompress(true);
        return config;
    }

    private final boolean tryEpoll;
    private InetSocketAddress endpoint;
    private int workThread;
    private MemoryMode memoryMode;
    private int connectTimeout = CONFIG.getSocksTimeout();
    private boolean enableSsl;
    private boolean enableCompress;
}
