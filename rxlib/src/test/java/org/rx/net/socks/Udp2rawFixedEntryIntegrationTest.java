package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.bean.DateTime;
import org.rx.core.RxConfig;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Udp2rawFixedEntryIntegrationTest {
    @Test
    @Timeout(20)
    void fixedEntryCreatesIndependentNatChannelsAndRepliesFromEntryPort() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-test-token");
        LinkedBlockingQueue<InetSocketAddress> echoSenders = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoSenders.add(msg.sender());
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdp2rawMaxSessions(8);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit");
            request.setMaxSessions(8);
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSupported());
            assertTrue(open.isSuccess());
            assertNotNull(open.getSessionSecret());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(5000);
            sendUdp2raw(client, entryAddress, open, 1001L,
                    new InetSocketAddress("127.0.0.1", 30001), echoAddress, "one");
            sendUdp2raw(client, entryAddress, open, 1002L,
                    new InetSocketAddress("127.0.0.1", 30002), echoAddress, "two");

            Set<String> responses = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                java.net.DatagramPacket packet = receive(client);
                assertEquals(entryAddress.getPort(), packet.getPort(), "response source must be fixed entry port");
                ByteBuf buf = Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength());
                try {
                    Udp2rawFrame frame = Udp2rawCodec.decode(buf);
                    assertEquals(Udp2rawFrameType.DATA, frame.getType());
                    assertTrue(frame.hasFlag(Udp2rawCodec.FLAG_HAS_SRC));
                    assertEquals(echoAddress.getPort(), frame.getSourceAddress().getPort());
                    responses.add(buf.toString(StandardCharsets.UTF_8));
                } finally {
                    buf.release();
                }
            }
            assertTrue(responses.contains("one"));
            assertTrue(responses.contains("two"));

            InetSocketAddress firstSender = echoSenders.poll(3, TimeUnit.SECONDS);
            InetSocketAddress secondSender = echoSenders.poll(3, TimeUnit.SECONDS);
            assertNotNull(firstSender);
            assertNotNull(secondSender);
            assertNotEquals(firstSender.getPort(), secondSender.getPort(),
                    "different client sourceEndpoint/connId must use different NAT source ports");
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryRepliesToAuthenticatedMtuProbe() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-mtu-probe-token");
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdpMtu(1300);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-mtu");
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(5000);
            sendRawUdp(client, entryAddress, buildMtuProbe(open, 77L, 1300));

            java.net.DatagramPacket packet = receive(client);
            assertEquals(entryAddress.getPort(), packet.getPort());
            ByteBuf buf = Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength());
            try {
                Udp2rawFrame frame = Udp2rawCodec.decode(buf);
                assertEquals(Udp2rawFrameType.MTU_ACK, frame.getType());
                assertEquals(77L, frame.getPacketSeq());
                assertTrue(frame.hasFlag(Udp2rawCodec.FLAG_AUTH_TAG));
                assertTrue(Udp2rawAuthenticator.verify(open.getSessionSecret(), frame, buf.slice()));
                assertEquals(4, buf.readableBytes());
                assertEquals(1300, buf.getInt(buf.readerIndex()));
            } finally {
                buf.release();
            }
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryCompressesResponseAndDropsRedundantRequest() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-p6-test-token");
        LinkedBlockingQueue<String> echoPayloads = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoPayloads.add(msg.content().toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdp2rawMaxSessions(8);
            config.setUdpCompressEnabled(true);
            config.setUdpCompressMinPayloadBytes(1);
            config.setUdpCompressMinSavingsBytes(1);
            config.setUdpCompressMinSavingsRatio(0.01D);
            config.setUdpRedundantMultiplier(2);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-p6");
            request.setMaxSessions(8);
            request.setCompress(UdpCompressConfig.fromSocksConfig(config));
            request.setRedundant(UdpRedundantConfig.fromSocksConfig(config));
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            assertTrue(open.getCapabilities().isCompress());
            assertTrue(open.getCapabilities().isRedundant());

            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());
            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(5000);

            byte[] bytes = buildCompressedUdp2raw(open,
                    new InetSocketAddress("127.0.0.1", 30101), echoAddress, repeat('C', 320));
            client.send(new java.net.DatagramPacket(bytes, bytes.length,
                    InetAddress.getByName(entryAddress.getHostString()), entryAddress.getPort()));
            client.send(new java.net.DatagramPacket(bytes, bytes.length,
                    InetAddress.getByName(entryAddress.getHostString()), entryAddress.getPort()));

            java.net.DatagramPacket packet = receive(client);
            assertEquals(entryAddress.getPort(), packet.getPort(), "response source must be fixed entry port");
            ByteBuf buf = Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength());
            ByteBuf decoded = null;
            try {
                Udp2rawFrame frame = Udp2rawCodec.decode(buf);
                assertTrue(frame.hasFlag(Udp2rawCodec.FLAG_COMPRESSED));
                assertTrue(frame.hasFlag(Udp2rawCodec.FLAG_REDUNDANT));
                decoded = Udp2rawPayloadSupport.decompress(UnpooledByteBufAllocator.DEFAULT, buf.slice(), "response-test");
                assertNotNull(decoded);
                assertEquals(repeat('C', 320), decoded.toString(StandardCharsets.UTF_8));
            } finally {
                if (decoded != null) {
                    decoded.release();
                }
                buf.release();
            }

            assertEquals(repeat('C', 320), echoPayloads.poll(3, TimeUnit.SECONDS));
            assertNull(echoPayloads.poll(300, TimeUnit.MILLISECONDS),
                    "same tunnel/conn/seq redundant request must not hit destination twice");
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryRequiresMacForPeerRebind() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-rebind-test-token");
        LinkedBlockingQueue<String> echoPayloads = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket firstPeer = null;
        DatagramSocket secondPeer = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoPayloads.add(msg.content().toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-rebind");
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            firstPeer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            secondPeer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            long connId = 3001L;
            sendRawUdp(firstPeer, entryAddress, buildUdp2raw(open, connId, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30201), echoAddress, "first", true));
            assertEquals("first", echoPayloads.poll(3, TimeUnit.SECONDS));

            sendRawUdp(secondPeer, entryAddress, buildUdp2raw(open, connId, 2L,
                    0, null, null, "rebind-without-mac", false));
            assertNull(echoPayloads.poll(400, TimeUnit.MILLISECONDS),
                    "peer rebind without authTag must not reach destination");

            sendRawUdp(secondPeer, entryAddress, buildUdp2raw(open, connId, 3L,
                    0, null, null, "rebind-with-mac", true));
            assertEquals("rebind-with-mac", echoPayloads.poll(3, TimeUnit.SECONDS));
        } finally {
            if (firstPeer != null) {
                firstPeer.close();
            }
            if (secondPeer != null) {
                secondPeer.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryBlocksPeerAfterBadAuthThreshold() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-bad-auth-test-token");
        LinkedBlockingQueue<String> echoPayloads = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoPayloads.add(msg.content().toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdp2rawBadAuthThreshold(1);
            config.setUdp2rawBadAuthFuseSeconds(1);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-bad-auth");
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            byte[] badAuth = buildUdp2raw(open, 4001L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30301), echoAddress, "bad-auth", true);
            badAuth[badAuth.length - 1] ^= 0x01;
            sendRawUdp(client, entryAddress, badAuth);
            Thread.sleep(100L);

            sendRawUdp(client, entryAddress, buildUdp2raw(open, 4002L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30302), echoAddress, "blocked", true));
            assertNull(echoPayloads.poll(500, TimeUnit.MILLISECONDS),
                    "peer should be blocked after reaching bad-auth threshold");

            Thread.sleep(1200L);
            sendRawUdp(client, entryAddress, buildUdp2raw(open, 4003L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30303), echoAddress, "after-fuse", true));
            assertEquals("after-fuse", echoPayloads.poll(3, TimeUnit.SECONDS));
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryRateLimitsPeerPackets() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-rate-limit-test-token");
        LinkedBlockingQueue<String> echoPayloads = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoPayloads.add(msg.content().toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdp2rawPeerRateLimitPerSecond(1);
            config.setUdp2rawPeerRateLimitBurst(1);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-rate-limit");
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            sendRawUdp(client, entryAddress, buildUdp2raw(open, 5001L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30401), echoAddress, "first", true));
            assertEquals("first", echoPayloads.poll(3, TimeUnit.SECONDS));

            sendRawUdp(client, entryAddress, buildUdp2raw(open, 5002L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30402), echoAddress, "limited", true));
            assertNull(echoPayloads.poll(500, TimeUnit.MILLISECONDS),
                    "same peer should be packet-rate limited within the one-second window");

            Thread.sleep(1100L);
            sendRawUdp(client, entryAddress, buildUdp2raw(open, 5003L, 1L,
                    Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST,
                    new InetSocketAddress("127.0.0.1", 30403), echoAddress, "after-limit", true));
            assertEquals("after-limit", echoPayloads.poll(3, TimeUnit.SECONDS));
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    @Test
    @Timeout(20)
    void fixedEntryBindsTrafficUserFromOpenRequest() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-traffic-test-token");
        LinkedBlockingQueue<String> echoPayloads = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoPayloads.add(msg.content().toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            TestTrafficUser trafficUser = new TestTrafficUser("udp2raw-u1");
            proxy = new SocksProxyServer(config, null);
            proxy.setConnectionTagResolver(tag -> {
                if (!"udp2raw-u1".equals(tag)) {
                    return null;
                }
                return new AuthResult(new SocksUser("inner-u1"), trafficUser);
            });

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit-traffic");
            request.setTrafficUser("udp2raw-u1");
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSuccess());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(5000);
            sendUdp2raw(client, entryAddress, open, 6001L,
                    new InetSocketAddress("127.0.0.1", 30501), echoAddress, "traffic");

            java.net.DatagramPacket packet = receive(client);
            ByteBuf buf = Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength());
            try {
                Udp2rawFrame frame = Udp2rawCodec.decode(buf);
                assertEquals(Udp2rawFrameType.DATA, frame.getType());
                assertEquals("traffic", buf.toString(StandardCharsets.UTF_8));
            } finally {
                buf.release();
            }
            assertEquals("traffic", echoPayloads.poll(3, TimeUnit.SECONDS));
            waitForTraffic(trafficUser, 2000L);
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    private static void sendUdp2raw(DatagramSocket socket, InetSocketAddress entryAddress,
            Udp2rawOpenResult open, long connId, InetSocketAddress clientSource,
            InetSocketAddress destination, String text) throws Exception {
        ByteBuf payload = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        Udp2rawFrame frame = Udp2rawFrame.data(open.getSessionHi(), open.getSessionLo(), connId, 1L);
        frame.setFlags(Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT
                | Udp2rawCodec.FLAG_HAS_DST | Udp2rawCodec.FLAG_AUTH_TAG);
        frame.setClientSource(clientSource);
        frame.setDestination(new UnresolvedEndpoint(destination.getHostString(), destination.getPort()));
        ByteBuf authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT,
                open.getSessionSecret(), frame, payload);
        frame.setAuthTag(authTag);
        ByteBuf encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            socket.send(new java.net.DatagramPacket(bytes, bytes.length,
                    InetAddress.getByName(entryAddress.getHostString()), entryAddress.getPort()));
        } finally {
            authTag.release();
            encoded.release();
        }
    }

    private static byte[] buildUdp2raw(Udp2rawOpenResult open, long connId, long seq, int flags,
            InetSocketAddress clientSource, InetSocketAddress destination, String text, boolean sign) {
        ByteBuf payload = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        ByteBuf authTag = null;
        ByteBuf encoded = null;
        try {
            Udp2rawFrame frame = Udp2rawFrame.data(open.getSessionHi(), open.getSessionLo(), connId, seq);
            if (sign) {
                flags |= Udp2rawCodec.FLAG_AUTH_TAG;
            }
            frame.setFlags(flags);
            if ((flags & Udp2rawCodec.FLAG_HAS_CLIENT) != 0) {
                frame.setClientSource(clientSource);
            }
            if ((flags & Udp2rawCodec.FLAG_HAS_DST) != 0) {
                frame.setDestination(new UnresolvedEndpoint(destination.getHostString(), destination.getPort()));
            }
            if (sign) {
                authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT,
                        open.getSessionSecret(), frame, payload);
                frame.setAuthTag(authTag);
            }
            encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
            payload = null;
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
        } finally {
            if (authTag != null) {
                authTag.release();
            }
            if (payload != null) {
                payload.release();
            }
            if (encoded != null) {
                encoded.release();
            }
        }
    }

    private static void sendRawUdp(DatagramSocket socket, InetSocketAddress entryAddress, byte[] bytes) throws Exception {
        socket.send(new java.net.DatagramPacket(bytes, bytes.length,
                InetAddress.getByName(entryAddress.getHostString()), entryAddress.getPort()));
    }

    private static byte[] buildMtuProbe(Udp2rawOpenResult open, long seq, int mtu) {
        ByteBuf payload = null;
        ByteBuf authTag = null;
        ByteBuf encoded = null;
        try {
            Udp2rawFrame frame = Udp2rawFrame.data(open.getSessionHi(), open.getSessionLo(), 0L, seq);
            frame.setType(Udp2rawFrameType.MTU_PROBE);
            frame.setFlags(Udp2rawCodec.FLAG_AUTH_TAG);
            int headerBytes = Udp2rawCodec.FIXED_HEADER_LENGTH + 1 + Udp2rawAuthenticator.DEFAULT_TAG_BYTES;
            int payloadBytes = Math.max(0, mtu - headerBytes);
            payload = UnpooledByteBufAllocator.DEFAULT.directBuffer(payloadBytes, payloadBytes);
            payload.writeZero(payloadBytes);
            authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT,
                    open.getSessionSecret(), frame, payload);
            frame.setAuthTag(authTag);
            encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
            payload = null;
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
        } finally {
            if (authTag != null) {
                authTag.release();
            }
            if (payload != null) {
                payload.release();
            }
            if (encoded != null) {
                encoded.release();
            }
        }
    }

    private static byte[] buildCompressedUdp2raw(Udp2rawOpenResult open,
            InetSocketAddress clientSource, InetSocketAddress destination, String text) {
        UdpCompressConfig compressConfig = new UdpCompressConfig();
        compressConfig.setEnabled(true);
        compressConfig.setMinPayloadBytes(1);
        compressConfig.setMinSavingsBytes(1);
        compressConfig.setMinSavingsRatio(0.01D);

        ByteBuf payload = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        ByteBuf body = payload;
        ByteBuf compressed = null;
        ByteBuf authTag = null;
        ByteBuf encoded = null;
        try {
            Udp2rawFrame frame = Udp2rawFrame.data(open.getSessionHi(), open.getSessionLo(), 2001L, 1L);
            int flags = Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT
                    | Udp2rawCodec.FLAG_HAS_DST | Udp2rawCodec.FLAG_AUTH_TAG
                    | Udp2rawCodec.FLAG_REDUNDANT;
            compressed = Udp2rawPayloadSupport.compress(UnpooledByteBufAllocator.DEFAULT, payload,
                    compressConfig, new UdpCompressStats(compressConfig), destination, "request-test");
            if (compressed != null) {
                flags |= Udp2rawCodec.FLAG_COMPRESSED;
                payload.release();
                payload = null;
                body = compressed;
                compressed = null;
            }
            frame.setFlags(flags);
            frame.setClientSource(clientSource);
            frame.setDestination(new UnresolvedEndpoint(destination.getHostString(), destination.getPort()));
            authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT,
                    open.getSessionSecret(), frame, body);
            frame.setAuthTag(authTag);
            encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, body);
            if (body == payload) {
                payload = null;
            }
            body = null;
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
        } finally {
            if (body == payload) {
                payload = null;
            }
            if (authTag != null) {
                authTag.release();
            }
            if (body != null) {
                body.release();
            }
            if (payload != null) {
                payload.release();
            }
            if (compressed != null) {
                compressed.release();
            }
            if (encoded != null) {
                encoded.release();
            }
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static java.net.DatagramPacket receive(DatagramSocket socket) throws Exception {
        byte[] bytes = new byte[1024];
        java.net.DatagramPacket packet = new java.net.DatagramPacket(bytes, bytes.length);
        socket.receive(packet);
        return packet;
    }

    private static void waitForTraffic(TestTrafficUser user, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!user.getLoginIps().isEmpty()
                    && user.getTotalReadBytes() > 0L
                    && user.getTotalWriteBytes() > 0L) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("traffic user should accumulate udp2raw read/write bytes");
    }

    static class TestTrafficUser implements TrafficUser {
        final String username;
        final Map<InetAddress, TrafficLoginInfo> loginIps = new ConcurrentHashMap<>();
        DateTime lastResetTime;

        TestTrafficUser(String username) {
            this.username = username;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public Map<InetAddress, TrafficLoginInfo> getLoginIps() {
            return loginIps;
        }

        @Override
        public int getIpLimit() {
            return -1;
        }

        @Override
        public DateTime getLastResetTime() {
            return lastResetTime;
        }

        @Override
        public void setLastResetTime(DateTime value) {
            lastResetTime = value;
        }

        @Override
        public boolean isAnonymous() {
            return false;
        }
    }
}
