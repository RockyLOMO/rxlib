package org.rx.net.socks;

import org.rx.net.udp.*;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.util.ReferenceCountUtil;
import org.rx.net.socks.upstream.Upstream;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class Socks5CommandRequestHandlerTest {
    @Test
    void connect_RouteFailureRepliesFailureWithoutThrowingToTail() {
        SocksConfig config = new SocksConfig(new LocalAddress("SOCKS5_ROUTE_FAILURE_TEST"));
        SocksProxyServer server = new SocksProxyServer(config, null);
        try {
            server.onTcpRoute.replace((s, e) -> {
                throw new InvalidException("No weighted socks upstream for {}", e.getSource().getAddress());
            });

            EmbeddedChannel channel = new EmbeddedChannel();
            channel.attr(SocksContext.SOCKS_SVR).set(server);
            Sockets.setOriginRemoteAddress(channel, new InetSocketAddress("101.228.2.116", 52000));
            channel.pipeline().addLast(Socks5CommandRequestDecoder.class.getSimpleName(), new ChannelInboundHandlerAdapter());
            channel.pipeline().addLast(Socks5CommandRequestHandler.class.getSimpleName(), Socks5CommandRequestHandler.DEFAULT);

            assertDoesNotThrow(() -> channel.writeInbound(new DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT, Socks5AddressType.IPv4, "1.1.1.1", 443)));
            Object outbound = channel.readOutbound();
            assertTrue(outbound instanceof Socks5CommandResponse);
            assertEquals(Socks5CommandStatus.FAILURE, ((Socks5CommandResponse) outbound).status());
            assertFalse(channel.finishAndReleaseAll());
        } finally {
            server.close();
        }
    }

    @Test
    void resolveUdpRelayBindAddress_UsesAnyLocalForLoopbackControl() {
        InetSocketAddress tcpLocalAddr = new InetSocketAddress("127.0.0.1", 6885);

        SocketAddress bindAddress = Socks5CommandRequestHandler.resolveUdpRelayBindAddress(tcpLocalAddr);

        assertTrue(bindAddress instanceof InetSocketAddress);
        InetSocketAddress inetBindAddress = (InetSocketAddress) bindAddress;
        assertNotNull(inetBindAddress.getAddress());
        assertTrue(inetBindAddress.getAddress().isAnyLocalAddress());
        assertEquals(0, inetBindAddress.getPort());
    }

    @Test
    void resolveUdpRelayAdvertiseAddress_KeepsLoopbackForClientReply() {
        InetSocketAddress tcpLocalAddr = new InetSocketAddress("127.0.0.1", 6885);
        InetSocketAddress udpBindLocalAddr = new InetSocketAddress("0.0.0.0", 2314);

        InetSocketAddress advertiseAddress = Socks5CommandRequestHandler.resolveUdpRelayAdvertiseAddress(tcpLocalAddr, udpBindLocalAddr);

        assertNotNull(advertiseAddress);
        assertEquals("127.0.0.1", advertiseAddress.getAddress().getHostAddress());
        assertEquals(2314, advertiseAddress.getPort());
    }

    @Test
    void resolveUdpRelayBindAddress_KeepsConcreteInterface() {
        InetSocketAddress tcpLocalAddr = new InetSocketAddress("192.168.31.4", 6885);

        SocketAddress bindAddress = Socks5CommandRequestHandler.resolveUdpRelayBindAddress(tcpLocalAddr);

        assertEquals(new InetSocketAddress("192.168.31.4", 0), bindAddress);
    }

    @Test
    void redundantClientPeerTrackingRequiresResponseDirectionForSamePeer() {
        SocksConfig config = new SocksConfig();
        config.setUdpRedundantMultiplier(2);
        InetSocketAddress client = new InetSocketAddress("127.0.0.1", 25001);

        assertFalse(Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(config, client, client));

        config.setSocksUdpRedundantMode(UdpRedundantMode.BIDIRECTIONAL);
        assertTrue(Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(config, client, client));
    }

    @Test
    void redundantClientPeerTrackingDoesNotUseDifferentPeerHeuristic() {
        SocksConfig config = new SocksConfig();
        config.setUdpRedundantMultiplier(2);
        InetSocketAddress origin = new InetSocketAddress("10.0.0.2", 25002);
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 25002);

        assertFalse(Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(config, origin, peer));

        config.setSocksUdpRedundantMode(UdpRedundantMode.RESPONSE_ONLY);
        assertTrue(Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(config, origin, peer));
    }

    @Test
    void maybeBypassTcpCompression_DoesNotRemoveLiveHandlers() throws Exception {
        SocksConfig config = new SocksConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());

        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        Sockets.addTcpClientHandler(inbound, config);
        Sockets.addTcpClientHandler(outbound, config);
        assertTrue(Sockets.hasTcpCompressionHandlers(inbound));
        assertTrue(Sockets.hasTcpCompressionHandlers(outbound));

        SocksContext e = SocksContext.getCtx(new InetSocketAddress("127.0.0.1", 18080), org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 443));
        Method method = Socks5CommandRequestHandler.class.getDeclaredMethod("maybeBypassTcpCompression",
                io.netty.channel.Channel.class, io.netty.channel.Channel.class, SocksContext.class, SocksConfig.class);
        method.setAccessible(true);
        method.invoke(Socks5CommandRequestHandler.DEFAULT, inbound, outbound, e, config);

        assertTrue(Sockets.hasTcpCompressionHandlers(inbound));
        assertTrue(Sockets.hasTcpCompressionHandlers(outbound));
    }

    @Test
    void relay_DomainConnectRepliesIpv4BindAddress() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("SOCKS5_DOMAIN_CONNECT_REPLY_TEST"));
        SocksProxyServer server = new SocksProxyServer(config, null);
        EmbeddedChannel inbound = new EmbeddedChannel(Socks5ServerEncoder.DEFAULT);
        EmbeddedChannel outbound = new EmbeddedChannel();
        try {
            inbound.attr(SocksContext.SOCKS_SVR).set(server);
            InetSocketAddress srcEp = new InetSocketAddress("127.0.0.1", 52000);
            InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 443);
            SocksContext e = SocksContext.getCtx(srcEp, dstEp);
            e.setUpstream(new Upstream(dstEp));
            SocksContext.markCtx(inbound, outbound, e);

            Method method = Socks5CommandRequestHandler.class.getDeclaredMethod("relay",
                    io.netty.channel.Channel.class, io.netty.channel.Channel.class, Socks5AddressType.class, SocksContext.class);
            method.setAccessible(true);
            method.invoke(Socks5CommandRequestHandler.DEFAULT, inbound, outbound, Socks5AddressType.DOMAIN, e);

            io.netty.buffer.ByteBuf response = inbound.readOutbound();
            assertNotNull(response);
            try {
                assertEquals(10, response.readableBytes());
                assertEquals(0x05, response.readUnsignedByte());
                assertEquals(0x00, response.readUnsignedByte());
                assertEquals(0x00, response.readUnsignedByte());
                assertEquals(Socks5AddressType.IPv4.byteValue(), response.readUnsignedByte());
                assertEquals(0, response.readInt());
                assertEquals(0, response.readUnsignedShort());
            } finally {
                ReferenceCountUtil.release(response);
            }
            assertFalse(inbound.finishAndReleaseAll());
            assertFalse(outbound.finishAndReleaseAll());
        } finally {
            server.close();
        }
    }
}
