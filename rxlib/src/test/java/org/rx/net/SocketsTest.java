package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.handler.codec.compression.JdkZlibEncoder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.diagnostic.DiagnosticMonitor;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.net.dns.DnsClient;
import org.rx.net.socks.SocksConfig;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class SocketsTest {
    private final List<String> originalDirectServers = new ArrayList<>(RxConfig.INSTANCE.getNet().getDns().getDirectServers());
    private final List<String> originalRemoteServers = new ArrayList<>(RxConfig.INSTANCE.getNet().getDns().getRemoteServers());

    @AfterEach
    void restoreTcpDnsResolver() throws Exception {
        Sockets.setInjectedNameServers(Collections.<InetSocketAddress>emptyList());
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().addAll(originalDirectServers);
        RxConfig.INSTANCE.getNet().getDns().getRemoteServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getRemoteServers().addAll(originalRemoteServers);
        for (String fieldName : new String[]{"tcpDirectDnsAddressResolverGroup", "tcpRemoteDnsAddressResolverGroup"}) {
            Field field = Sockets.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, null);
        }
        closeAndClearDnsClient("directClient");
        closeAndClearDnsClient("remoteClient");
    }

    private static void closeAndClearDnsClient(String fieldName) throws Exception {
        Field field = DnsClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        DnsClient client = (DnsClient) field.get(null);
        field.set(null, null);
        if (client != null && !client.isClosed()) {
            client.close();
        }
    }

    @Test
    public void testReactor() {
        EventLoopGroup group = Sockets.reactor("testReactor", true);
        assertNotNull(group);
        if (Epoll.isAvailable()) {
            assertTrue(group instanceof EpollEventLoopGroup);
        } else {
            assertTrue(group instanceof NioEventLoopGroup);
        }
    }

    @Test
    public void testServerBootstrap() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ServerBootstrap sb = Sockets.serverBootstrap(ch -> {
            latch.countDown();
        });
        assertNotNull(sb);

        // We bind to an ephemeral port to test if initChannel is called
        Channel serverChannel = sb.bind(0).sync().channel();
        assertNotNull(serverChannel);

        try {
            // Need a client to connect to trigger initChannel
            Bootstrap b = Sockets.bootstrap(null, ch -> {
            });
            Channel clientChannel = b.connect("127.0.0.1", ((InetSocketAddress) serverChannel.localAddress()).getPort()).sync().channel();


            assertTrue(latch.await(5, TimeUnit.SECONDS));
            clientChannel.close();
        } finally {
            serverChannel.close();
            Sockets.closeBootstrap(sb);
        }
    }

    @Test
    public void testUdpBootstrap() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Bootstrap b = Sockets.udpBootstrap(null, ch -> {
            latch.countDown();
        });
        assertNotNull(b);
        // UDP initChannel is called on bind
        Channel channel = b.bind(0).sync().channel();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        channel.close();
    }

    @Test
    public void testUdpWriteDropsWhenPendingBytesExceedLimit() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(4);

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.PENDING_OVERLIMIT,
                Sockets.writeUdp(channel, packet, "test.udp", "case=overlimit"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteAcceptedClearsPendingBytes() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(128);

        ByteBuf payload = Unpooled.copiedBuffer("udp-ok", StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.ACCEPTED,
                Sockets.writeUdp(channel, packet, "test.udp", "case=accepted"));
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();

        DatagramPacket outbound = channel.readOutbound();
        assertNotNull(outbound);
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        outbound.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void testWriteUdpUsesConfiguredUdpWriteLimit() {
        SocketConfig config = new SocketConfig();
        config.setUdpWriteLimitBytes(6);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6, 7});
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.PENDING_OVERLIMIT,
                Sockets.writeUdp(channel, packet, "test.udp", "case=config-limit"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteMtuDisabledAllowsLargerPacket() {
        SocketConfig config = new SocketConfig();
        config.setUdpMtu(0);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.ACCEPTED,
                Sockets.writeUdp(channel, packet, "test.udp", "case=mtu-disabled"));

        DatagramPacket outbound = channel.readOutbound();
        assertNotNull(outbound);
        outbound.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteAllowsPacketAtConfiguredUdpMtu() {
        SocketConfig config = new SocketConfig();
        config.setUdpMtu(4);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4});
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.ACCEPTED,
                Sockets.writeUdp(channel, packet, "test.udp", "case=mtu-equal"));

        DatagramPacket outbound = channel.readOutbound();
        assertNotNull(outbound);
        outbound.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteDropsWhenConfiguredUdpMtuExceeded() {
        SocketConfig config = new SocketConfig();
        config.setUdpMtu(4);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.MTU_EXCEEDED,
                Sockets.writeUdp(channel, packet, "test.udp", "case=mtu-exceeded"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteRecordsMtuDropMetricsWithLowCardinalityTags() throws Exception {
        DiagnosticConfig config = memDiagnosticConfig("sockets_udp_mtu_drop");
        config.setSampleIntervalMillis(60000L);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            SocketConfig socketConfig = new SocketConfig();
            socketConfig.setUdpMtu(1300);
            EmbeddedChannel channel = new EmbeddedChannel();
            channel.attr(SocketConfig.ATTR_CONF).set(socketConfig);

            ByteBuf payload = Unpooled.buffer(1301);
            payload.writeZero(1301);
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertEquals(Sockets.UdpWriteResult.MTU_EXCEEDED,
                    Sockets.writeUdp(channel, packet, "test.udp", "case=mtu-metric"));
            assertEquals(0, payload.refCnt());

            assertTrue(monitor.getStore().flush(5000L));
            String tags = "case=mtu-metric,reason=mtu-exceeded,mtuBucket=lte1300";
            assertEquals(1, countMetric(config, "test.udp.drop.count", tags));
            assertEquals(1, countMetric(config, "test.udp.mtu.drop.count", tags));
            assertEquals(1, countMetric(config, "test.udp.mtu.drop.bytes", tags));
            assertEquals(0, countWhere(config, "diag_metric_sample",
                    "metric='test.udp.mtu.drop.count' AND (tags LIKE '%bytes=%' OR tags LIKE '%recipient=%' OR tags LIKE '%127.0.0.1%')"));
            channel.finishAndReleaseAll();
        } finally {
            monitor.close();
        }
    }

    @Test
    public void testUdpWriteDropsUnresolvedRecipientBeforeWrite() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(128);

        ByteBuf payload = Unpooled.copiedBuffer("udp-unresolved", StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, InetSocketAddress.createUnresolved("example.invalid", 53));

        assertEquals(Sockets.UdpWriteResult.UNRESOLVED_RECIPIENT,
                Sockets.writeUdp(channel, packet, "test.udp", "case=unresolved"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpPipelineResolvesDirectUnresolvedIpLiteralWrite() {
        SocketConfig config = new SocketConfig();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);
        Sockets.addUdpOptimizationHandlers(channel.pipeline(), config);

        ByteBuf payload = Unpooled.copiedBuffer("udp-unresolved-ip", StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, InetSocketAddress.createUnresolved("127.0.0.1", 53));

        ChannelFuture future = channel.writeAndFlush(packet);
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();

        assertTrue(future.isSuccess());
        DatagramPacket outbound = channel.readOutbound();
        assertNotNull(outbound);
        assertFalse(outbound.recipient().isUnresolved());
        assertEquals("127.0.0.1", outbound.recipient().getAddress().getHostAddress());
        outbound.release();
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteDropsWhenChannelUnwritable() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(128);
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.copiedBuffer("udp-unwritable", StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.CHANNEL_UNWRITABLE,
                Sockets.writeUdp(channel, packet, "test.udp", "case=unwritable"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());

        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteDropsWhenChannelInactive() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close();

        ByteBuf payload = Unpooled.copiedBuffer("udp-inactive", StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

        assertEquals(Sockets.UdpWriteResult.CHANNEL_INACTIVE,
                Sockets.writeUdp(channel, packet, "test.udp", "case=inactive"));
        assertEquals(0, payload.refCnt());
        assertEquals(0, Sockets.udpPendingWriteBytes(channel));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testUdpWriteDropsWhenWriteThrowsAndRecordsLowCardinalityMetrics() throws Exception {
        DiagnosticConfig config = memDiagnosticConfig("sockets_udp_write_throw");
        config.setSampleIntervalMillis(60000L);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            EmbeddedChannel channel = new ThrowingWriteEmbeddedChannel();
            channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(128);

            ByteBuf payload = Unpooled.copiedBuffer("udp-write-throw", StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertEquals(Sockets.UdpWriteResult.WRITE_THROWN,
                    Sockets.writeUdp(channel, packet, "test.udp", "case=write-throw"));
            assertEquals(0, payload.refCnt());
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertNull(channel.readOutbound());

            assertTrue(monitor.getStore().flush(5000L));
            String tags = "case=write-throw,reason=write-throw,limitBucket=lte64k";
            assertEquals(1, countMetric(config, "test.udp.drop.count", tags));
            assertEquals(1, countMetric(config, "test.udp.pending.write.bytes", tags));
            assertEquals(0, countWhere(config, "diag_metric_sample",
                    "metric='test.udp.drop.count' AND (tags LIKE '%pendingBytes=%' OR tags LIKE '%recipient=%' OR tags LIKE '%127.0.0.1%')"));
            channel.finishAndReleaseAll();
        } finally {
            monitor.close();
        }
    }

    @Test
    public void testParseEndpoint() {
        InetSocketAddress address = Sockets.parseEndpoint("127.0.0.1:8080");
        assertEquals("127.0.0.1", address.getAddress().getHostAddress());
        assertEquals(8080, address.getPort());

        address = Sockets.parseEndpoint("google.com:80");
        assertEquals("google.com", address.getHostString());
        assertEquals(80, address.getPort());
        assertNull(address.getAddress(), "域名 endpoint 不应在本地解析");

        assertThrows(Exception.class, () -> Sockets.parseEndpoint("invalid"));
    }

    @Test
    public void testParseEndpointSupportsIpv6BracketAndUnbracketedLiteral() {
        InetSocketAddress bracket = Sockets.parseEndpoint("[::1]:8080");
        assertNotNull(bracket.getAddress());
        assertEquals(8080, bracket.getPort());

        InetSocketAddress literal = Sockets.parseEndpoint("2001:db8::1:8443");
        assertNotNull(literal.getAddress());
        assertEquals(8443, literal.getPort());

        assertThrows(Exception.class, () -> Sockets.parseEndpoint("[::1]"));
        assertThrows(Exception.class, () -> Sockets.parseEndpoint("::1"));
    }

    @Test
    public void testSetHttpProxyAcceptsDomainWithoutLocalDns() {
        try {
            Sockets.setHttpProxy("proxy.example:8080");

            assertEquals("proxy.example", System.getProperty("http.proxyHost"));
            assertEquals("8080", System.getProperty("http.proxyPort"));
            assertEquals("proxy.example", System.getProperty("https.proxyHost"));
            assertEquals("8080", System.getProperty("https.proxyPort"));
        } finally {
            Sockets.clearHttpProxy();
        }
    }

    @Test
    public void testGetMessageBuf() {
        ByteBuf buf = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);

        // Test with ByteBuf
        assertEquals(buf, Sockets.getMessageBuf(buf));

        // Test with DatagramPacket
        DatagramPacket packet = new DatagramPacket(buf, new InetSocketAddress(0));
        assertEquals(buf, Sockets.getMessageBuf(packet));
    }

    @SneakyThrows
    @Test
    public void testInjectNameService() {
        String host = "rxlib-" + UUID.randomUUID() + ".test";
        String negativeHost = "rxlib-negative-" + UUID.randomUUID() + ".test";
        String blockedHost = "rxlib-blocked-" + UUID.randomUUID() + ".test";
        InetAddress expected = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 123});
        try {
            Sockets.injectNameService((srcIp, h) -> {
                if (host.equals(h)) {
                    return Collections.singletonList(expected);
                }
                if (negativeHost.equals(h)) {
                    return Collections.emptyList();
                }
                if (blockedHost.equals(h)) {
                    throw new IllegalStateException("blocked");
                }
                return null;
            });

            InetAddress actual = InetAddress.getByName(host);
            assertEquals(expected.getHostAddress(), actual.getHostAddress());
            assertThrows(UnknownHostException.class, () -> InetAddress.getByName(negativeHost));
            Exception blocked = assertThrows(Exception.class, () -> InetAddress.getByName(blockedHost));
            assertTrue(blocked instanceof IllegalStateException
                    || blocked instanceof UnknownHostException && blocked.getCause() instanceof IllegalStateException);
            assertNotNull(Sockets.nsInterceptor);
        } finally {
            // Keep proxy installed but restore interceptor to delegate to the platform resolver.
            Sockets.nsInterceptor = (srcIp, h) -> null;
        }
    }

    @Test
    public void testIsPrivateIp() throws Exception {
        assertTrue(Sockets.isPrivateIp(InetAddress.getByName("127.0.0.1")));
        assertTrue(Sockets.isPrivateIp(InetAddress.getByName("192.168.1.1")));
        assertTrue(Sockets.isPrivateIp(InetAddress.getByName("10.0.0.1")));
        assertFalse(Sockets.isPrivateIp(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void testNewEndpoint() {
        InetSocketAddress ep = Sockets.newEndpoint("127.0.0.1:8080", 9090);
        assertEquals(9090, ep.getPort());
        assertEquals("127.0.0.1", ep.getHostString());

        InetSocketAddress unresolved = Sockets.newEndpoint(Sockets.parseEndpoint("example.com:8080"), 9090);
        assertEquals(9090, unresolved.getPort());
        assertEquals("example.com", unresolved.getHostString());
        assertNull(unresolved.getAddress(), "域名改端口后仍保持 unresolved");
    }

    @Test
    public void testNewUnresolvedEndpointKeepsDomainUnresolved() {
        InetSocketAddress endpoint = org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 443);
        InetSocketAddress address = endpoint;
        assertEquals("example.com", address.getHostString());
        assertEquals(443, address.getPort());
        assertNull(address.getAddress(), "域名目的地必须交给远端解析");

        InetSocketAddress ipEndpoint = org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", 443);
        assertEquals("127.0.0.1", ipEndpoint.getAddress().getHostAddress());
    }

    @Test
    public void testAddRpcTcpClientHandler() {
        SocketConfig config = new SocketConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_READ.flags());
 
        EmbeddedChannel channel = new EmbeddedChannel();
        Sockets.addTcpClientHandler(channel, config);
 
        assertNotNull(channel.pipeline().get(Sockets.ZIP_DECODER));
        assertNull(channel.pipeline().get(Sockets.ZIP_ENCODER));
 
        config.setTransportFlags(TransportFlags.COMPRESS_WRITE.flags());
        channel = new EmbeddedChannel();
        Sockets.addTcpClientHandler(channel, config);
 
        assertNull(channel.pipeline().get(Sockets.ZIP_DECODER));
        assertNotNull(channel.pipeline().get(Sockets.ZIP_ENCODER));
    }

    @Test
    public void testPipelineAddBeforeAndAfterKeepRequestedOrder() {
        EmbeddedChannel beforeChannel = new EmbeddedChannel();
        try {
            beforeChannel.pipeline().addLast("base", new BasePipelineHandler());
            Sockets.addBefore(beforeChannel.pipeline(), "base",
                    new FirstPipelineHandler(), new SecondPipelineHandler());

            List<String> beforeNames = beforeChannel.pipeline().names();
            assertTrue(beforeNames.contains(FirstPipelineHandler.class.getSimpleName()));
            assertTrue(beforeNames.contains(SecondPipelineHandler.class.getSimpleName()));
            assertTrue(beforeNames.contains("base"));
            assertTrue(beforeNames.indexOf(FirstPipelineHandler.class.getSimpleName())
                    < beforeNames.indexOf(SecondPipelineHandler.class.getSimpleName()));
            assertTrue(beforeNames.indexOf(SecondPipelineHandler.class.getSimpleName())
                    < beforeNames.indexOf("base"));
        } finally {
            beforeChannel.finishAndReleaseAll();
        }

        EmbeddedChannel afterChannel = new EmbeddedChannel();
        try {
            afterChannel.pipeline().addLast("base", new BasePipelineHandler());
            Sockets.addAfter(afterChannel.pipeline(), "base",
                    new FirstPipelineHandler(), new SecondPipelineHandler());

            List<String> afterNames = afterChannel.pipeline().names();
            assertTrue(afterNames.contains("base"));
            assertTrue(afterNames.contains(FirstPipelineHandler.class.getSimpleName()));
            assertTrue(afterNames.contains(SecondPipelineHandler.class.getSimpleName()));
            assertTrue(afterNames.indexOf("base")
                    < afterNames.indexOf(FirstPipelineHandler.class.getSimpleName()));
            assertTrue(afterNames.indexOf(FirstPipelineHandler.class.getSimpleName())
                    < afterNames.indexOf(SecondPipelineHandler.class.getSimpleName()));
        } finally {
            afterChannel.finishAndReleaseAll();
        }
    }

    @Test
    public void testTcpCompressionLevelUsesConfiguredEncoder() {
        SocketConfig config = new SocketConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_WRITE.flags());
        config.setTcpCompressionLevel(5);

        EmbeddedChannel channel = new EmbeddedChannel();
        Sockets.addTcpClientHandler(channel, config);

        assertTrue(channel.pipeline().get(Sockets.ZIP_ENCODER) instanceof JdkZlibEncoder);
    }

    @Test
    public void testTcpClientCompressionEncoderDefersUntilActive() throws Exception {
        SocketConfig config = new SocketConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_WRITE.flags());

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel unconnectedChannel = new NioSocketChannel();
        try {
            group.register(unconnectedChannel).syncUninterruptibly();
            assertFalse(unconnectedChannel.isActive());
            Sockets.addTcpClientHandler(unconnectedChannel, config);

            assertTrue(unconnectedChannel.pipeline().get(Sockets.ZIP_ENCODER) instanceof Sockets.ActiveTcpCompressionEncoderInstaller);
            assertFalse(unconnectedChannel.pipeline().get(Sockets.ZIP_ENCODER) instanceof JdkZlibEncoder);
            assertTrue(unconnectedChannel.close().syncUninterruptibly().isSuccess());
        } finally {
            if (unconnectedChannel.isOpen()) {
                unconnectedChannel.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }

        EmbeddedChannel channel = new EmbeddedChannel(false, false);
        Sockets.addTcpClientHandler(channel, config);

        assertTrue(channel.pipeline().get(Sockets.ZIP_ENCODER) instanceof Sockets.ActiveTcpCompressionEncoderInstaller);
        assertFalse(channel.pipeline().get(Sockets.ZIP_ENCODER) instanceof JdkZlibEncoder);

        channel.register();
        assertTrue(channel.pipeline().get(Sockets.ZIP_ENCODER) instanceof JdkZlibEncoder);
        channel.finishAndReleaseAll();
    }

    @Test
    public void testRemoveTcpCompressionHandlers() {
        SocketConfig config = new SocketConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());

        EmbeddedChannel channel = new EmbeddedChannel();
        Sockets.addTcpClientHandler(channel, config);

        assertTrue(Sockets.hasTcpCompressionHandlers(channel));
        assertTrue(Sockets.removeTcpCompressionHandlers(channel));
        assertFalse(Sockets.hasTcpCompressionHandlers(channel));
        assertNull(channel.pipeline().get(Sockets.ZIP_DECODER));
        assertNull(channel.pipeline().get(Sockets.ZIP_ENCODER));
    }

    @Test
    public void testShouldBypassTcpCompressionForEncryptedPorts() {
        assertTrue(Sockets.shouldBypassTcpCompression(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 443)));
        assertTrue(Sockets.shouldBypassTcpCompression(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 993)));
        assertFalse(Sockets.shouldBypassTcpCompression(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 80)));
    }

    @Test
    public void testTcpDnsResolverUsesConfiguredRemoteDns() throws Exception {
        RxConfig.INSTANCE.getNet().getDns().getRemoteServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getRemoteServers().add("127.0.0.1:5353");

        Object resolverGroup = Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.REMOTE);
        Field builderField = resolverGroup.getClass().getDeclaredField("dnsResolverBuilder");
        builderField.setAccessible(true);
        Object builder = builderField.get(resolverGroup);

        Field providerField = builder.getClass().getDeclaredField("dnsServerAddressStreamProvider");
        providerField.setAccessible(true);
        DnsServerAddressStreamProvider provider = (DnsServerAddressStreamProvider) providerField.get(builder);
        assertNotNull(provider);
        assertTrue(provider.getClass().getName().contains("DnsClient$DnsServerAddressStreamProviderImpl"), provider.getClass().getName());

        InetSocketAddress first = provider.nameServerAddressStream("mail.proton.me").next();
        assertEquals("127.0.0.1", first.getHostString());
        assertEquals(5353, first.getPort());
    }

    @Test
    public void testBootstrapSystemDnsModeDisablesNettyResolver() {
        SocksConfig config = new SocksConfig();
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.SYSTEM);

        Bootstrap bootstrap = Sockets.bootstrap(config, ch -> {
        });
        assertSame(DefaultAddressResolverGroup.INSTANCE, bootstrap.config().resolver());
    }

    @Test
    public void testBootstrapDirectDnsModeUsesDirectResolver() {
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().add("127.0.0.1:5353");

        SocksConfig config = new SocksConfig();
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.DIRECT);

        Bootstrap bootstrap = Sockets.bootstrap(config, ch -> {
        });
        assertSame(Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.DIRECT), bootstrap.config().resolver());
    }

    @Test
    public void testInjectedNameServersPrependDirectDnsServers() throws Exception {
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().add("192.168.31.1:53");
        Sockets.setInjectedNameServers(Collections.singletonList(Sockets.parseEndpoint("127.0.0.1:753")));

        List<InetSocketAddress> directServers = DnsClient.directNameServers();
        assertEquals("127.0.0.1", directServers.get(0).getHostString());
        assertEquals(753, directServers.get(0).getPort());
        assertEquals("192.168.31.1", directServers.get(1).getHostString());
        assertEquals(53, directServers.get(1).getPort());

        Object resolverGroup = Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.DIRECT);
        Field builderField = resolverGroup.getClass().getDeclaredField("dnsResolverBuilder");
        builderField.setAccessible(true);
        Object builder = builderField.get(resolverGroup);

        Field providerField = builder.getClass().getDeclaredField("dnsServerAddressStreamProvider");
        providerField.setAccessible(true);
        DnsServerAddressStreamProvider provider = (DnsServerAddressStreamProvider) providerField.get(builder);
        InetSocketAddress first = provider.nameServerAddressStream("svc-mercury").next();
        assertEquals("127.0.0.1", first.getHostString());
        assertEquals(753, first.getPort());
    }

    @Test
    public void testBootstrapDefaultUsesSystemResolverWhenDirectDnsUnset() {
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().clear();

        Bootstrap bootstrap = Sockets.bootstrap(new SocketConfig(), ch -> {
        });
        assertSame(DefaultAddressResolverGroup.INSTANCE, bootstrap.config().resolver());
    }

    @Test
    public void testRefreshTcpResolverPicksInjectedNameserverAfterBootstrapCreated() {
        RxConfig.INSTANCE.getNet().getDns().getDirectServers().clear();

        Bootstrap bootstrap = Sockets.bootstrap(new SocketConfig(), ch -> {
        });
        assertSame(DefaultAddressResolverGroup.INSTANCE, bootstrap.config().resolver());

        Sockets.setInjectedNameServers(Collections.singletonList(Sockets.parseEndpoint("127.0.0.1:753")));
        Sockets.refreshTcpResolver(bootstrap, Sockets.newUnresolvedEndpoint("svc-mercury", 1211));

        assertNotSame(DefaultAddressResolverGroup.INSTANCE, bootstrap.config().resolver());
        assertSame(Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.DIRECT), bootstrap.config().resolver());
    }

    private DiagnosticConfig memDiagnosticConfig(String name) {
        DiagnosticConfig config = new DiagnosticConfig();
        config.setH2JdbcUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        config.setH2QueueSize(64);
        config.setH2BatchSize(16);
        config.setH2FlushIntervalMillis(50L);
        config.setH2TtlMillis(0L);
        config.setDiagnosticsDirectory(new java.io.File("target/diagnostics-test"));
        config.setDiagnosticsMaxBytes(0L);
        config.setEvidenceMinFreeBytes(0L);
        config.setJfrMinFreeBytes(0L);
        config.setHeapDumpMinFreeBytes(0L);
        config.setHeavyEvidenceCooldownMillis(0L);
        return config;
    }

    private int countMetric(DiagnosticConfig config, String metric, String tags) throws Exception {
        return countWhere(config, "diag_metric_sample", "metric='" + metric + "' AND tags='" + tags + "'");
    }

    private int countWhere(DiagnosticConfig config, String table, String where) throws Exception {
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static final class ThrowingWriteEmbeddedChannel extends EmbeddedChannel {
        private ThrowingWriteEmbeddedChannel() {
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            throw new IllegalStateException("synthetic write failure");
        }
    }

    private static final class BasePipelineHandler extends ChannelInboundHandlerAdapter {
    }

    private static final class FirstPipelineHandler extends ChannelInboundHandlerAdapter {
    }

    private static final class SecondPipelineHandler extends ChannelInboundHandlerAdapter {
    }
}
