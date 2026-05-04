package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void routePendingReleaseReleasesRetainedPackets() throws Exception {
        SSUdpProxyHandler handler = new SSUdpProxyHandler();
        SSUdpProxyHandler.RouteInitState initState = new SSUdpProxyHandler.RouteInitState();
        SSUdpProxyHandler.RouteKey routeKey = new SSUdpProxyHandler.RouteKey(
                new InetSocketAddress("127.0.0.1", 10004),
                new UnresolvedEndpoint("127.0.0.1", 53));
        ByteBuf payload = Bytes.directBuffer(8).writeLong(1L);

        Method enqueue = SSUdpProxyHandler.class.getDeclaredMethod("enqueuePendingPacket",
                SSUdpProxyHandler.RouteInitState.class, ByteBuf.class, SSUdpProxyHandler.RouteKey.class);
        enqueue.setAccessible(true);
        Method release = SSUdpProxyHandler.class.getDeclaredMethod("releasePending", SSUdpProxyHandler.RouteInitState.class);
        release.setAccessible(true);

        try {
            assertTrue((Boolean) enqueue.invoke(handler, initState, payload, routeKey));
            assertEquals(2, payload.refCnt());

            ReferenceCountUtil.release(payload);
            assertEquals(1, payload.refCnt());

            release.invoke(handler, initState);
            assertEquals(0, payload.refCnt());
        } finally {
            if (payload.refCnt() > 0) {
                ReferenceCountUtil.release(payload);
            }
        }
    }

    @Test
    void outboundPoolSourceCountReleasedWhenOpenThrows() {
        SSUdpProxyHandler.OUTBOUND_POOL.clear();
        SSUdpProxyHandler.OUTBOUND_POOL_SOURCE_COUNTS.clear();
        EmbeddedChannel inbound = new EmbeddedChannel();
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(0),
                CipherKind.AES_256_GCM.getCipherName(), "open-throws");
        config.setUdpOutboundPoolMaxPerSource(1);
        ShadowsocksServer server = new ShadowsocksServer(config);
        try {
            SSUdpProxyHandler handler = new SSUdpProxyHandler() {
                @Override
                ChannelFuture openOutboundChannel(Channel inbound, ShadowsocksServer server, Upstream upstream,
                                                   InetSocketAddress srcEp, OutboundPoolKey key) {
                    throw new IllegalStateException("synthetic open failure");
                }
            };
            SSUdpProxyHandler.RouteKey routeKey = new SSUdpProxyHandler.RouteKey(
                    new InetSocketAddress("127.0.0.1", 10003),
                    new UnresolvedEndpoint("127.0.0.1", 53));
            Upstream upstream = new Upstream(routeKey.destination, config);

            assertThrows(IllegalStateException.class,
                    () -> handler.acquireOutboundChannel(inbound, server, routeKey, upstream));
            assertEquals(0, SSUdpProxyHandler.OUTBOUND_POOL_SOURCE_COUNTS.size());
        } finally {
            server.close();
            inbound.finishAndReleaseAll();
            SSUdpProxyHandler.OUTBOUND_POOL.clear();
            SSUdpProxyHandler.OUTBOUND_POOL_SOURCE_COUNTS.clear();
        }
    }
}
