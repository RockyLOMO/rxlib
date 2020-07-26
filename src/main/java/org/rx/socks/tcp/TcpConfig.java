package org.rx.socks.tcp;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.socks.MemoryMode;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;

import static org.rx.core.Contract.CONFIG;

@Data
@RequiredArgsConstructor
public class TcpConfig {
    public static TcpClient client(boolean tryEpoll, InetSocketAddress serverEndpoint, String groupId) {
        return new TcpClient(packetConfig(tryEpoll, serverEndpoint), new HandshakePacket(groupId));
    }

    public static <T extends Serializable> TcpServer<T> server(boolean tryEpoll, int port, Class<T> stateType) {
        return new TcpServer<>(packetConfig(tryEpoll, Sockets.getAnyEndpoint(port)), stateType);
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
