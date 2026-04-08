package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.concurrent.Future;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Socks5ClientIntegrationTest {
    private static final int PROXY_PORT = 16800;
    private static final int ECHO_PORT = 16801;
    private static final int UDP_ECHO_PORT = 16802;

    private SocksProxyServer proxyServer;
    private Channel echoServer;
    private Channel udpEchoServer;

    @BeforeEach
    public void setup() throws Exception {
        SocksConfig config = new SocksConfig(PROXY_PORT);
        config.setDebug(true);
        proxyServer = new SocksProxyServer(config);

        echoServer = Sockets.serverBootstrap(ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                    ctx.writeAndFlush(msg.retain());
                }
            });
        }).bind(ECHO_PORT).sync().channel();

        udpEchoServer = Sockets.udpBootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                }
            });
        }).bind(UDP_ECHO_PORT).sync().channel();
    }

    @AfterEach
    public void tearDown() {
        if (proxyServer != null) proxyServer.close();
        if (echoServer != null) echoServer.close();
        if (udpEchoServer != null) udpEchoServer.close();
    }

    @Test
    public void testConnect() throws Exception {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + PROXY_PORT))) {
            String message = "hello-socks5-tcp";
            CompletableFuture<String> result = new CompletableFuture<>();

            client.connect(new UnresolvedEndpoint("127.0.0.1", ECHO_PORT)).addListener(f -> {
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

    @Test
    @SneakyThrows
    void testUdpAssociate() {
        String message = "hello-socks5-udp";
        System.out.println("DEBUG: Starting RAW UDP associate test...");
        CompletableFuture<InetSocketAddress> relayAddrFuture = new CompletableFuture<>();

        Bootstrap b = Sockets.bootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new Socks5ClientEncoder(), new Socks5InitialRequestDecoder(), new SimpleChannelInboundHandler<Socks5InitialResponse>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, Socks5InitialResponse msg) {
                    System.out.println("DEBUG: Received Socks5InitialResponse: " + msg.authMethod());
                    ctx.pipeline().replace(this, "command", new Socks5CommandResponseDecoder());
                    ctx.pipeline().addBefore("command", "command-encoder", new Socks5ClientEncoder());
                    ctx.writeAndFlush(new DefaultSocks5CommandRequest(Socks5CommandType.UDP_ASSOCIATE, Socks5AddressType.IPv4, "127.0.0.1", 0));
                }
            });
            ch.pipeline().addLast(new SimpleChannelInboundHandler<Socks5CommandResponse>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, Socks5CommandResponse msg) {
                    System.out.println("DEBUG: Received Socks5CommandResponse: " + msg.status() + " " + msg.bndAddr() + ":" + msg.bndPort());
                    if (msg.status() == Socks5CommandStatus.SUCCESS) {
                        relayAddrFuture.complete(new InetSocketAddress(msg.bndAddr(), msg.bndPort()));
                    } else {
                        relayAddrFuture.completeExceptionally(new RuntimeException("Status: " + msg.status()));
                    }
                }
            });
        });

        Channel tcpCh = b.connect("127.0.0.1", PROXY_PORT).sync().channel();
        InetSocketAddress relayAddr = relayAddrFuture.get(10, TimeUnit.SECONDS);
        System.out.println("DEBUG: Got relay address: " + relayAddr);

        // Now send UDP
        Bootstrap udpB = Sockets.udpBootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    System.out.println("DEBUG: RAW UDP received from " + msg.sender());
                }
            });
        });
        Channel udpCh = udpB.bind(0).sync().channel();

        ByteBuf payload = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
        CompositeByteBuf packet = UdpManager.socks5Encode(payload, new UnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT));
        udpCh.writeAndFlush(new DatagramPacket(packet, relayAddr)).sync();
        System.out.println("DEBUG: UDP packet sent to " + relayAddr);

        // Wait for response
        Thread.sleep(2000);
        tcpCh.close();
        udpCh.close();
    }
}
