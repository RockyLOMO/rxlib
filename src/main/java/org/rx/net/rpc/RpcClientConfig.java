package org.rx.net.rpc;

import lombok.Data;
import org.rx.core.App;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.rx.core.Contract.require;

@Data
public class RpcClientConfig implements Serializable {
    public static final int NON_POOL_SIZE = -1;
    public static final int DEFAULT_VERSION = 0;

    public static RpcClientConfig statefulMode(String serverEndpoint, int eventVersion) {
        return statefulMode(Sockets.parseEndpoint(serverEndpoint), eventVersion);
    }

    public static RpcClientConfig statefulMode(InetSocketAddress serverEndpoint, int eventVersion) {
        RpcClientConfig config = new RpcClientConfig();
        config.setServerEndpoint(serverEndpoint);
        config.setAutoReconnect(true);
        config.setEventVersion(eventVersion);
        return config;
    }

    public static RpcClientConfig poolMode(String serverEndpoint, int maxPoolSize) {
        return poolMode(Sockets.parseEndpoint(serverEndpoint), maxPoolSize);
    }

    public static RpcClientConfig poolMode(InetSocketAddress serverEndpoint, int maxPoolSize) {
        RpcClientConfig config = new RpcClientConfig();
        config.setServerEndpoint(serverEndpoint);
        config.setAutoReconnect(false);
        config.setMaxPoolSize(maxPoolSize);
        return config;
    }

    private static final long serialVersionUID = -4952694662640163676L;
    private boolean tryEpoll = true;
    private InetSocketAddress serverEndpoint;
    private MemoryMode memoryMode;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
    private boolean enableSsl;
    private boolean enableCompress;
    private boolean autoReconnect;
    private int eventVersion = DEFAULT_VERSION;
    private int maxPoolSize = NON_POOL_SIZE;

    public boolean isUsePool() {
        return maxPoolSize > NON_POOL_SIZE;
    }

    public UUID hashValue() {
        require(serverEndpoint);

        return App.hash(serverEndpoint, enableSsl, enableCompress, eventVersion, isUsePool());
    }
}