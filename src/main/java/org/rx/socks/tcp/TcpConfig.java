package org.rx.socks.tcp;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.socks.MemoryMode;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;

import static org.rx.core.App.Config;

@Data
@RequiredArgsConstructor
public class TcpConfig {
    public static TcpClient client(InetSocketAddress serverEndpoint, String groupId) {
        return new TcpClient(packetConfig(serverEndpoint), new HandshakePacket(groupId));
    }

    public static <T extends Serializable> TcpServer<T> server(int port, Class<T> stateType) {
        return new TcpServer<>(packetConfig(Sockets.getAnyEndpoint(port)), stateType);
    }

    private static TcpConfig packetConfig(InetSocketAddress endpoint) {
        TcpConfig config = new TcpConfig();
        config.setEndpoint(endpoint);
        config.setEnableSsl(false);
        config.setEnableCompress(true);
        return config;
    }

    private InetSocketAddress endpoint;
    private int workThread;
    private MemoryMode memoryMode;
    private int connectTimeout = Config.getSocksTimeout();
    private boolean enableSsl;
    private boolean enableCompress;
}
