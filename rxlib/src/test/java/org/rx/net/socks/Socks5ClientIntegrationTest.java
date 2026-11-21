package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.Future;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.Sockets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class Socks5ClientIntegrationTest {
    private SocksProxyServer proxyServer;
    private Channel echoServer;
    private ServerBootstrap echoBootstrap;
    private Channel udpEchoServer;
    private int proxyPort;
    private int echoPort;
    private int udpEchoPort;

    @BeforeEach
    public void setup() throws Exception {
        final InetSocketAddress[] proxyAddress = new InetSocketAddress[1];
        SocksConfig config = new SocksConfig(0);
        config.setDebug(true);
        proxyServer = new SocksProxyServer(config, null, ch -> proxyAddress[0] = (InetSocketAddress) ch.localAddress());
        proxyPort = proxyAddress[0].getPort();

        echoBootstrap = Sockets.serverBootstrap(ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                    ctx.writeAndFlush(msg.retain());
                }
            });
        });
        echoServer = echoBootstrap.bind(0).sync().channel();
        echoPort = ((InetSocketAddress) echoServer.localAddress()).getPort();

        udpEchoServer = Sockets.udpBootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                }
            });
        }).bind(0).sync().channel();
        udpEchoPort = ((InetSocketAddress) udpEchoServer.localAddress()).getPort();
    }

    @AfterEach
    public void tearDown() {
        if (proxyServer != null) proxyServer.close();
        if (echoServer != null) echoServer.close().syncUninterruptibly();
        Sockets.closeBootstrap(echoBootstrap);
        if (udpEchoServer != null) udpEchoServer.close().syncUninterruptibly();
    }

    @Test
    void refusedDestinationWithNoReconnectHandlerMakesOneAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Socket reserved = new Socket();
             Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + proxyPort))) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress target = (InetSocketAddress) reserved.getLocalSocketAddress();
            proxyServer.onTcpRoute.replace((s, e) -> e.setUpstream(new Upstream(target) {
                @Override
                public void initChannel(Channel channel) {
                    attempts.incrementAndGet();
                }
            }));
            CompletableFuture<Boolean> connected = new CompletableFuture<>();
            client.connect(target).addListener(f -> connected.complete(f.isSuccess()));
            assertFalse(connected.get(5, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
        }
    }

    static Stream<Arguments> addressFamilies() throws Exception {
        String[] hosts = NetworkInterface.getByInetAddress(InetAddress.getByName("::1")) == null
                || Boolean.getBoolean("java.net.preferIPv4Stack") ? new String[]{"127.0.0.1"} : new String[]{"127.0.0.1", "::1"};
        return Stream.of(hosts).flatMap(proxy -> Stream.of(hosts).map(echo -> Arguments.of(proxy, echo)));
    }

    @ParameterizedTest
    @MethodSource("addressFamilies")
    public void testConnect(String proxyHost, String echoHost) throws Exception {
        try (Socks5Client client = new Socks5Client(new AuthenticEndpoint(Sockets.newUnresolvedEndpoint(proxyHost, proxyPort), null, null))) {
            String message = "hello-socks5-tcp";
            CompletableFuture<String> result = new CompletableFuture<>();

            client.connect(Sockets.newUnresolvedEndpoint(echoHost, echoPort)).addListener(f -> {
                if (!f.isSuccess()) {
                    result.completeExceptionally(f.cause());
                    return;
                }
                Channel ch = (Channel) ((Future<?>) f).getNow();
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                        result.complete(msg.toString(StandardCharsets.UTF_8));
                    }
                });
                ch.writeAndFlush(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
            });

            assertEquals(message, result.get(15, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("addressFamilies")
    @SneakyThrows
    void testUdpAssociate(String proxyHost, String echoHost) {
        try (Socks5Client client = new Socks5Client(new AuthenticEndpoint(Sockets.newUnresolvedEndpoint(proxyHost, proxyPort), null, null))) {
            String message = "hello-socks5-udp";
            CompletableFuture<String> result = new CompletableFuture<>();

            System.out.println("DEBUG: Starting UDP associate...");
            Socks5Client.Socks5UdpSession session = client.udpAssociateAsync().get(30, TimeUnit.SECONDS);
            System.out.println("DEBUG: UDP session started, relay addr: " + session.getRelayAddress());
            assertNotNull(session.getUdpRelay());

            session.onReceive.add((s, e) -> {
                DatagramPacket pkt = e.getValue();
                try {
                    String received = pkt.content().toString(StandardCharsets.UTF_8);
                    System.out.println("DEBUG: UDP session received from " + pkt.sender() + ": " + received);
                    result.complete(received);
                } finally {
                    pkt.content().release();
                }
            });

            CompletableFuture<String> second = new CompletableFuture<>();
            session.onReceive.add((s, e) -> {
                DatagramPacket packet = e.getValue();
                try {
                    second.complete(packet.content().toString(StandardCharsets.UTF_8));
                } finally {
                    packet.release();
                }
            });

            System.out.println("DEBUG: Sending UDP packet to " + udpEchoPort);
            session.send(Sockets.newUnresolvedEndpoint(echoHost, udpEchoPort),
                    Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)).addListener(f -> {
                if (!f.isSuccess()) {
                    result.completeExceptionally(f.cause());
                }
            });

            assertEquals(message, result.get(15, TimeUnit.SECONDS));
            assertEquals(message, second.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @SneakyThrows
    void testUdpAssociateWithProvidedChannel() {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + proxyPort))) {
            String message = "hello-socks5-udp-provided";
            CompletableFuture<String> result = new CompletableFuture<>();

            // 1. Manually create a UDP channel
            Channel udpChannel = Sockets.udpBootstrap(new SocketConfig(), ch -> {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                        // This handler is part of the manual pipeline
                        ByteBuf content = msg.content();
                        try {
                             InetSocketAddress src = UdpManager.socks5Decode(content);
                             String received = content.toString(StandardCharsets.UTF_8);
                             System.out.println("DEBUG: Provided channel received from " + src + ": " + received);
                             result.complete(received);
                        } finally {
                            // No need to release here if we use SimpleChannelInboundHandler's auto-release,
                            // but usually it's better to be explicit if we are not sure.
                        }
                    }
                });
            }).bind(0).sync().channel();

            try {
                System.out.println("DEBUG: Starting UDP associate with provided channel...");
                // 2. Associate using the provided channel
                Socks5Client.Socks5UdpSession session = client.udpAssociateAsync(udpChannel).get(30, TimeUnit.SECONDS);
                System.out.println("DEBUG: UDP session started with provided channel, relay addr: " + session.getRelayAddress());
                assertEquals(udpChannel, session.getUdpRelay());

                // 3. Send using session
                System.out.println("DEBUG: Sending UDP packet via session to " + udpEchoPort);
                session.send(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", udpEchoPort),
                        Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));

                assertEquals(message, result.get(15, TimeUnit.SECONDS));
            } finally {
                udpChannel.close();
            }
        }
    }
}
