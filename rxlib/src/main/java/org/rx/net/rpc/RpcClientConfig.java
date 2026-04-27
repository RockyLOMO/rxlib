package org.rx.net.rpc;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.Sockets;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridConfig;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;

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

    private final HybridConfig hybridConfig;
    private short eventVersion = DEFAULT_VERSION;
    private short minPoolSize;
    private short maxPoolSize = NON_POOL_SIZE;
    private int requestTimeoutMillis = -1;
    private TripleAction<T, HybridClient> initHandler;
    private RemotingCodecFactory codecFactory = FuryRemotingCodecFactory.createDefault();

    public RpcClientConfig(TcpClientConfig tcpConfig) {
        this(new HybridConfig());
        hybridConfig.setTcpClientConfig(tcpConfig);
    }

    public RpcClientConfig(HybridConfig hybridConfig) {
        this.hybridConfig = hybridConfig == null ? new HybridConfig() : hybridConfig;
        if (this.hybridConfig.getTcpClientConfig() == null) {
            this.hybridConfig.setTcpClientConfig(new TcpClientConfig());
        }
    }

    public TcpClientConfig getTcpConfig() {
        return hybridConfig.getTcpClientConfig();
    }

    public boolean isUsePool() {
        return maxPoolSize > NON_POOL_SIZE;
    }
}
