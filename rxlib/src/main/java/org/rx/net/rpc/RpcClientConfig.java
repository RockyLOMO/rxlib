package org.rx.net.rpc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.Sockets;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class RpcClientConfig<T> {
    private static final long serialVersionUID = -4952694662640163676L;
    public static final int NON_POOL_SIZE = -1;
    public static final int DEFAULT_VERSION = 0;

    public static <T> RpcClientConfig<T> statefulMode(String serverEndpoint, int eventVersion) {
        return statefulMode(Sockets.parseEndpoint(serverEndpoint), eventVersion);
    }

    public static <T> RpcClientConfig<T> statefulMode(InetSocketAddress serverEndpoint, int eventVersion) {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        tcpClientConfig.setServerEndpoint(serverEndpoint);
        tcpClientConfig.setEnableReconnect(true);
        RpcClientConfig<T> config = new RpcClientConfig<>(tcpClientConfig);
        config.setEventVersion(eventVersion);
        return config;
    }

    public static <T> RpcClientConfig<T> poolMode(String serverEndpoint, int maxPoolSize) {
        return poolMode(Sockets.parseEndpoint(serverEndpoint), 2, maxPoolSize);
    }

    public static <T> RpcClientConfig<T> poolMode(InetSocketAddress serverEndpoint, int minPoolSize, int maxPoolSize) {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        tcpClientConfig.setServerEndpoint(serverEndpoint);
        tcpClientConfig.setEnableReconnect(false);
        RpcClientConfig<T> config = new RpcClientConfig<>(tcpClientConfig);
        config.setMinPoolSize(minPoolSize);
        config.setMaxPoolSize(maxPoolSize);
        return config;
    }

    private final TcpClientConfig tcpConfig;
    private int eventVersion = DEFAULT_VERSION;
    private int minPoolSize;
    private int maxPoolSize = NON_POOL_SIZE;
    private TripleAction<T, StatefulTcpClient> initHandler;

    public boolean isUsePool() {
        return maxPoolSize > NON_POOL_SIZE;
    }
}
