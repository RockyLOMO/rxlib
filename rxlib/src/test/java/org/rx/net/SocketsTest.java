package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;
import org.rx.net.dns.DnsClient;
import org.rx.net.socks.SocksConfig;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class SocketsTest {
    private final List<String> originalOutlandServers = new ArrayList<>(RxConfig.INSTANCE.getNet().getDns().getOutlandServers());

    @AfterEach
    void restoreTcpDnsResolver() throws Exception {
        RxConfig.INSTANCE.getNet().getDns().getOutlandServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getOutlandServers().addAll(originalOutlandServers);
        for (String fieldName : new String[]{"tcpInlandDnsAddressResolverGroup", "tcpOutlandDnsAddressResolverGroup"}) {
            Field field = Sockets.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, null);
        }
        closeAndClearDnsClient("inlandClient");
        closeAndClearDnsClient("outlandClient");
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
    public void testParseEndpoint() {
        InetSocketAddress address = Sockets.parseEndpoint("127.0.0.1:8080");
        assertEquals("127.0.0.1", address.getAddress().getHostAddress());
        assertEquals(8080, address.getPort());

        address = Sockets.parseEndpoint("google.com:80");
        assertEquals("google.com", address.getHostString());
        assertEquals(80, address.getPort());

        assertThrows(Exception.class, () -> Sockets.parseEndpoint("invalid"));
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
        // This modifies static state, so it might affect other tests or be affected by them.
        // We'll test if we can simply call it without error.
        Sockets.injectNameService((srcIp, host) -> Collections.emptyList());

        // Verify it was set
        assertNotNull(Sockets.nsInterceptor);
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
    }

    @Test
    public void testAddTcpClientHandler() {
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
    public void testTcpDnsResolverUsesConfiguredOutlandDns() throws Exception {
        RxConfig.INSTANCE.getNet().getDns().getOutlandServers().clear();
        RxConfig.INSTANCE.getNet().getDns().getOutlandServers().add("127.0.0.1:5353");

        Object resolverGroup = Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.OUTLAND);
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
    public void testBootstrapInlandDnsModeUsesInlandResolver() {
        SocksConfig config = new SocksConfig();
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.INLAND);

        Bootstrap bootstrap = Sockets.bootstrap(config, ch -> {
        });
        assertSame(Sockets.tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.INLAND), bootstrap.config().resolver());
    }

    @Test
    public void testBootstrapDefaultUsesSystemResolver() {
        Bootstrap bootstrap = Sockets.bootstrap(new SocketConfig(), ch -> {
        });
        assertSame(DefaultAddressResolverGroup.INSTANCE, bootstrap.config().resolver());
    }
}
