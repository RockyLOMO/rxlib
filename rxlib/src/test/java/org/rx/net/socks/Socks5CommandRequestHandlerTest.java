package org.rx.net.socks;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class Socks5CommandRequestHandlerTest {
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
    void maybeBypassTcpCompression_DoesNotRemoveLiveHandlers() throws Exception {
        SocksConfig config = new SocksConfig();
        config.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());

        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        Sockets.addTcpClientHandler(inbound, config);
        Sockets.addTcpClientHandler(outbound, config);
        assertTrue(Sockets.hasTcpCompressionHandlers(inbound));
        assertTrue(Sockets.hasTcpCompressionHandlers(outbound));

        SocksContext e = SocksContext.getCtx(new InetSocketAddress("127.0.0.1", 18080), new org.rx.net.support.UnresolvedEndpoint("example.com", 443));
        Method method = Socks5CommandRequestHandler.class.getDeclaredMethod("maybeBypassTcpCompression",
                io.netty.channel.Channel.class, io.netty.channel.Channel.class, SocksContext.class, SocksConfig.class);
        method.setAccessible(true);
        method.invoke(Socks5CommandRequestHandler.DEFAULT, inbound, outbound, e, config);

        assertTrue(Sockets.hasTcpCompressionHandlers(inbound));
        assertTrue(Sockets.hasTcpCompressionHandlers(outbound));
    }
}
