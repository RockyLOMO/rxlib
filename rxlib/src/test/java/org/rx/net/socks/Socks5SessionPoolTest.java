package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import java.net.InetSocketAddress;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Socks5SessionPoolTest {
    @Test
    void tcpWarmPoolKey_usesEndpointSnapshotEquality() {
        SocksConfig config = new SocksConfig(1080);
        AuthenticEndpoint left = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 18080), "u", "p");
        AuthenticEndpoint right = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 18080), "u", "p");

        TcpWarmPoolKey leftKey = TcpWarmPoolKey.from(left, config, "reactor-a");
        TcpWarmPoolKey rightKey = TcpWarmPoolKey.from(right, config, "reactor-a");

        assertEquals(leftKey, rightKey);
        assertEquals(leftKey.hashCode(), rightKey.hashCode());

        config.setAdaptiveTcp(true);
        config.setAdaptiveProfile(1);
        TcpWarmPoolKey game = TcpWarmPoolKey.from(left, config, "reactor-a");
        config.setAdaptiveProfile(2);
        TcpWarmPoolKey weak = TcpWarmPoolKey.from(left, config, "reactor-a");
        assertNotEquals(game, weak);
        config.setTcpCompressionLevel(1);
        assertNotEquals(weak, TcpWarmPoolKey.from(left, config, "reactor-a"));
    }

    @Test
    void serverSelectsOnlyAnOfferedProfile() {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            assertFalse(SocksAdaptiveHandshake.select(ch.pipeline().firstContext(),
                    java.util.Collections.singletonList(new Socks5AuthMethod(0x86))));
            assertNull(ch.readOutbound());
            for (int profile = 0; profile <= 2; profile++) {
                assertTrue(SocksAdaptiveHandshake.select(ch.pipeline().firstContext(), SocksAdaptiveHandshake.offer(profile).authMethods()));
                DefaultSocks5InitialResponse response = ch.readOutbound();
                assertTrue(SocksAdaptiveHandshake.selected(profile, response.authMethod()));
                assertFalse(SocksAdaptiveHandshake.selected((profile + 1) % 3, response.authMethod()));
                assertEquals(Integer.valueOf(profile), ch.attr(SocksAdaptiveHandshake.NEGOTIATED).get());
            }
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void warmChannelRejectsAnUnadvertisedAdaptiveMethod() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 1080);
        Socks5WarmupHandler warm = new Socks5WarmupHandler(address, "u", "p", 0);
        warm.setAdaptiveTcp(true);
        warm.setAdaptiveProfile(1);
        EmbeddedChannel ch = new EmbeddedChannel(warm) {
            @Override protected java.net.SocketAddress remoteAddress0() { return address; }
        };
        try {
            ByteBuf greeting = ch.readOutbound();
            try {
                assertEquals(0x84, greeting.getUnsignedByte(greeting.writerIndex() - 1));
            } finally {
                greeting.release();
            }
            ch.writeInbound(new DefaultSocks5InitialResponse(new Socks5AuthMethod(0x85)));
            java.util.concurrent.CompletionException error = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> warm.readyFuture().join());
            assertTrue(error.getCause() instanceof ProxyConnectException);
            assertNull(ch.pipeline().get(AdaptiveTcpHandler.class));
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void udpLeasePoolKey_usesEndpointSnapshotEquality() {
        SocksConfig config = new SocksConfig(1080);
        AuthenticEndpoint left = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 18081), "u", "p");
        AuthenticEndpoint right = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 18081), "u", "p");

        UdpLeasePoolKey leftKey = UdpLeasePoolKey.from(left, config, "reactor-b");
        UdpLeasePoolKey rightKey = UdpLeasePoolKey.from(right, config, "reactor-b");

        assertEquals(leftKey, rightKey);
        assertEquals(leftKey.hashCode(), rightKey.hashCode());
    }

    @Test
    void udpRelayIdleHint_appliesSafetyMargin() {
        HashMap<String, String> parameters = new HashMap<>();
        parameters.put("udpRelayIdleSeconds", "45");
        AuthenticEndpoint endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 19080), null, null, parameters);
        SocksUdpUpstream upstream = new SocksUdpUpstream(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 53), new SocksConfig(1080),
                new UpstreamSupport(endpoint, null));

        assertEquals(40_000L, upstream.resolveRelayIdleHintMillis());
    }

    @Test
    void warmupHandler_callbackRunsBeforePromiseSuccess() {
        Socks5WarmupHandler handler = new Socks5WarmupHandler(new InetSocketAddress("127.0.0.1", 1080), null, null, 5000);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
            assertTrue(handler.readyFuture().isDone());

            List<String> events = new ArrayList<>();
            handler.setConnectedCallback(() -> events.add("callback"));
            ChannelFuture future = handler.connect(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 443));
            future.addListener(f -> events.add("promise"));

            channel.writeInbound(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN));

            assertEquals(2, events.size());
            assertEquals("callback", events.get(0));
            assertEquals("promise", events.get(1));
            assertNull(channel.pipeline().get(Socks5WarmupHandler.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
