package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.hybrid.HybridConfig;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotingHybridConfigTest {
    @Test
    void statefulModeBuildsHybridClientConfig() {
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25001);
        RpcClientConfig<Object> config = RpcClientConfig.statefulMode(endpoint, 7);

        assertSame(config.getTcpConfig(), config.getHybridConfig().getTcpClientConfig());
        assertEquals(endpoint, config.getTcpConfig().getServerEndpoint());
        assertTrue(config.getTcpConfig().isEnableReconnect());
        assertEquals(7, config.getEventVersion());
        assertFalse(config.isUsePool());
    }

    @Test
    void poolModeBuildsHybridClientConfig() {
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25002);
        RpcClientConfig<Object> config = RpcClientConfig.poolMode(endpoint, 2, 4);

        assertSame(config.getTcpConfig(), config.getHybridConfig().getTcpClientConfig());
        assertEquals(endpoint, config.getTcpConfig().getServerEndpoint());
        assertFalse(config.getTcpConfig().isEnableReconnect());
        assertEquals(2, config.getMinPoolSize());
        assertEquals(4, config.getMaxPoolSize());
        assertTrue(config.isUsePool());
    }

    @Test
    void constructorPreservesHybridServerConfig() {
        HybridConfig hybridConfig = new HybridConfig();
        TcpServerConfig tcpServerConfig = new TcpServerConfig(26001);
        hybridConfig.setTcpServerConfig(tcpServerConfig);

        RpcServerConfig config = new RpcServerConfig(hybridConfig);
        assertSame(hybridConfig, config.getHybridConfig());
        assertSame(tcpServerConfig, config.getTcpConfig());
    }

    @Test
    void constructorWrapsTcpClientConfig() {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        RpcClientConfig<Object> config = new RpcClientConfig<Object>(tcpClientConfig);

        assertSame(tcpClientConfig, config.getTcpConfig());
        assertSame(tcpClientConfig, config.getHybridConfig().getTcpClientConfig());
    }
}
