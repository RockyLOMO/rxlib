package org.rx.net.rpc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import javax.validation.constraints.NotNull;
import java.net.InetSocketAddress;

@Data
@EqualsAndHashCode(callSuper = true)
public class RpcClientConfig extends SocketConfig {
    public static final String REACTOR_NAME = "℞Rpc";
    public static final int NON_RECONNECT = -1;
    public static final int NON_POOL_SIZE = -1;
    public static final int DEFAULT_VERSION = 0;

    public static RpcClientConfig statefulMode(String serverEndpoint, int eventVersion) {
        return statefulMode(Sockets.parseEndpoint(serverEndpoint), eventVersion);
    }

    public static RpcClientConfig statefulMode(InetSocketAddress serverEndpoint, int eventVersion) {
        RpcClientConfig config = new RpcClientConfig();
        config.setServerEndpoint(serverEndpoint);
        config.setReconnectPeriod(2000);
        config.setEventVersion(eventVersion);
        return config;
    }

    public static RpcClientConfig poolMode(String serverEndpoint, int maxPoolSize) {
        return poolMode(Sockets.parseEndpoint(serverEndpoint), 2, maxPoolSize);
    }

    public static RpcClientConfig poolMode(InetSocketAddress serverEndpoint, int minPoolSize, int maxPoolSize) {
        RpcClientConfig config = new RpcClientConfig();
        config.setServerEndpoint(serverEndpoint);
        config.setReconnectPeriod(NON_RECONNECT);
        config.setMinPoolSize(minPoolSize);
        config.setMaxPoolSize(maxPoolSize);
        return config;
    }

    private static final long serialVersionUID = -4952694662640163676L;
    @NotNull
    private InetSocketAddress serverEndpoint;
    private int reconnectPeriod = NON_RECONNECT;
    private int eventVersion = DEFAULT_VERSION;
    private int minPoolSize;
    private int maxPoolSize = NON_POOL_SIZE;

    public boolean isUsePool() {
        return maxPoolSize > NON_POOL_SIZE;
    }
}
