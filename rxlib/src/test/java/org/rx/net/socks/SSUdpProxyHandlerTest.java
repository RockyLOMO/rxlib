package org.rx.net.socks;

import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SSUdpProxyHandlerTest {
    @Test
    void outboundIdleFallback_appliesWhenServerTimeoutDisabled() {
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(0),
                CipherKind.AES_256_GCM.getCipherName(), "idle-fallback");
        config.setUdpReadTimeoutSeconds(0);
        config.setUdpWriteTimeoutSeconds(0);

        assertEquals(SSUdpProxyHandler.DEFAULT_OUTBOUND_IDLE_SECONDS,
                SSUdpProxyHandler.resolveOutboundReadIdleSeconds(config));
        assertEquals(0, SSUdpProxyHandler.resolveOutboundWriteIdleSeconds(config));
    }

    @Test
    void outboundIdleFallback_respectsExplicitServerTimeout() {
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(0),
                CipherKind.AES_256_GCM.getCipherName(), "idle-explicit");
        config.setUdpReadTimeoutSeconds(45);
        config.setUdpWriteTimeoutSeconds(7);

        assertEquals(45, SSUdpProxyHandler.resolveOutboundReadIdleSeconds(config));
        assertEquals(7, SSUdpProxyHandler.resolveOutboundWriteIdleSeconds(config));
    }
}
