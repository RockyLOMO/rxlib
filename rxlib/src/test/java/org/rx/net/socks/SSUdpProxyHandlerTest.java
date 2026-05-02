package org.rx.net.socks;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void perSourcePendingLimitDropsOnlyOffendingSource() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        InetSocketAddress sourceA = new InetSocketAddress("127.0.0.1", 10001);
        InetSocketAddress sourceB = new InetSocketAddress("127.0.0.1", 10002);

        assertTrue(SSUdpProxyHandler.reserveSourcePending(inbound, sourceA, 4, 8));
        assertFalse(SSUdpProxyHandler.reserveSourcePending(inbound, sourceA, 5, 8));
        assertEquals(4, SSUdpProxyHandler.sourcePendingBytes(inbound, sourceA));

        assertTrue(SSUdpProxyHandler.reserveSourcePending(inbound, sourceB, 8, 8));
        assertEquals(8, SSUdpProxyHandler.sourcePendingBytes(inbound, sourceB));

        SSUdpProxyHandler.releaseSourcePending(inbound, sourceA, 4);
        SSUdpProxyHandler.releaseSourcePending(inbound, sourceB, 8);
        assertEquals(0, SSUdpProxyHandler.sourcePendingBytes(inbound, sourceA));
        assertEquals(0, SSUdpProxyHandler.sourcePendingBytes(inbound, sourceB));
        inbound.finishAndReleaseAll();
    }
}
