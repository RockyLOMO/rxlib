package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.core.TransportConfig;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class RemotingTransportConfigTest {
    @Test
    void statefulModeBuildsTransportClientConfig() {
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25001);
        RpcClientConfig<Object> config = RpcClientConfig.statefulMode(endpoint, 7);

        assertSame(config.getTcpConfig(), config.getTransportConfig().getTcpClientConfig());
        assertEquals(endpoint, config.getTcpConfig().getServerEndpoint());
        assertTrue(config.getTcpConfig().isEnableReconnect());
        assertTrue(config.getTransportConfig().isEnableAdaptiveUdp());
        assertEquals(7, config.getEventVersion());
        assertFalse(config.isUsePool());
    }

    @Test
    void poolModeBuildsTransportClientConfig() {
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25002);
        RpcClientConfig<Object> config = RpcClientConfig.poolMode(endpoint, 2, 4);

        assertSame(config.getTcpConfig(), config.getTransportConfig().getTcpClientConfig());
        assertEquals(endpoint, config.getTcpConfig().getServerEndpoint());
        assertFalse(config.getTcpConfig().isEnableReconnect());
        assertEquals(2, config.getMinPoolSize());
        assertEquals(4, config.getMaxPoolSize());
        assertTrue(config.isUsePool());
    }

    @Test
    void constructorPreservesTransportServerConfig() {
        TransportConfig transportConfig = new TransportConfig();
        TcpServerConfig tcpServerConfig = new TcpServerConfig(26001);
        transportConfig.setTcpServerConfig(tcpServerConfig);

        RpcServerConfig config = new RpcServerConfig(transportConfig);
        assertSame(transportConfig, config.getTransportConfig());
        assertSame(tcpServerConfig, config.getTcpConfig());
    }

    @Test
    void constructorWrapsTcpClientConfig() {
        TcpClientConfig tcpClientConfig = new TcpClientConfig();
        RpcClientConfig<Object> config = new RpcClientConfig<Object>(tcpClientConfig);

        assertSame(tcpClientConfig, config.getTcpConfig());
        assertSame(tcpClientConfig, config.getTransportConfig().getTcpClientConfig());
    }
}
