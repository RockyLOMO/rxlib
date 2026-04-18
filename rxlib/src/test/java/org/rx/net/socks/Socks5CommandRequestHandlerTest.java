package org.rx.net.socks;

import org.junit.jupiter.api.Test;

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
}
