package org.rx.net.rpc;

import io.netty.channel.local.LocalAddress;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.Sockets;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;

@Getter
@Setter
@ToString
public class RpcClientConfig<T> {
    private static final long serialVersionUID = -4952694662640163676L;
    public static final short NON_POOL_SIZE = -1;
    public static final short DEFAULT_VERSION = 0;

    public static <T> RpcClientConfig<T> statefulMode(String serverEndpoint, int eventVersion) {
        return statefulMode(Sockets.parseEndpoint(serverEndpoint), eventVersion);
    }

    public static <T> RpcClientConfig<T> statefulMode(InetSocketAddress serverEndpoint, int eventVersion) {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        tcpClientConfig.setServerEndpoint(serverEndpoint);
        tcpClientConfig.setEnableReconnect(true);
        RpcClientConfig<T> config = new RpcClientConfig<T>(tcpClientConfig);
        config.setEventVersion((short) eventVersion);
        return config;
    }

    public static <T> RpcClientConfig<T> localCarrier(LocalAddress localAddress, TransportConfig transportConfig) {
        TransportConfig tc = transportConfig == null ? TransportConfig.pureTcpClient(null) : transportConfig;
        if (tc.getTcpClientConfig() == null) {
            tc.setTcpClientConfig(new TcpClientConfig());
        }
        tc.getTcpClientConfig().setEnableReconnect(false);
        RpcClientConfig<T> config = new RpcClientConfig<T>(tc);
        config.setLocalAddress(localAddress);
        return config;
    }

    public static <T> RpcClientConfig<T> poolMode(String serverEndpoint, int maxPoolSize) {
        return poolMode(Sockets.parseEndpoint(serverEndpoint), 2, maxPoolSize);
    }

    public static <T> RpcClientConfig<T> poolMode(InetSocketAddress serverEndpoint, int minPoolSize, int maxPoolSize) {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        tcpClientConfig.setServerEndpoint(serverEndpoint);
        tcpClientConfig.setEnableReconnect(false);
        RpcClientConfig<T> config = new RpcClientConfig<T>(tcpClientConfig);
        config.setMinPoolSize((short) minPoolSize);
        config.setMaxPoolSize((short) maxPoolSize);
        return config;
    }

    private final TransportConfig transportConfig;
    /** Optional in-process LocalAddress virtual carrier (e.g. RRP RAW Relay stream bridge). */
    private LocalAddress localAddress;
    private short eventVersion = DEFAULT_VERSION;
    private short minPoolSize;
    private short maxPoolSize = NON_POOL_SIZE;
    private int requestTimeoutMillis = -1;
    private TripleAction<T, TransportClient> initHandler;
    private RemotingCodecFactory codecFactory = FuryRemotingCodecFactory.createDefault();
    /** Method arguments omitted from call logs and trace snapshots (credentials). */
    private Set<String> sensitiveMethods = Collections.emptySet();
    /**
     * Methods explicitly safe to replay after an in-flight disconnect/reconnect.
     * Non-idempotent methods (default) fail fast with ClientDisconnectedException once transmitted
     * and are NEVER replayed after reconnect.
     */
    private Set<String> idempotentMethods = Collections.emptySet();

    public boolean isIdempotentMethod(String methodName) {
        return methodName != null && idempotentMethods != null && idempotentMethods.contains(methodName);
    }

    public RpcClientConfig(TcpClientConfig tcpConfig) {
        this(new TransportConfig());
        transportConfig.setTcpClientConfig(tcpConfig);
    }

    public RpcClientConfig(TransportConfig transportConfig) {
        this.transportConfig = transportConfig == null ? new TransportConfig() : transportConfig;
        if (this.transportConfig.getTcpClientConfig() == null) {
            this.transportConfig.setTcpClientConfig(new TcpClientConfig());
        }
    }

    public TcpClientConfig getTcpConfig() {
        return transportConfig.getTcpClientConfig();
    }

    public boolean isUsePool() {
        return maxPoolSize > NON_POOL_SIZE;
    }
}
