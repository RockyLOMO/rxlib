package org.rx.net.socks;

import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class Socks5ClientTest {
    @Test
    public void resolveRelayAddress_prefersActualConnectedRemoteForWildcardReply() {
        Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("example.invalid:1080"));
        InetSocketAddress bindAddr = new InetSocketAddress("0.0.0.0", 53000);
        InetSocketAddress actualRemote = new InetSocketAddress("198.51.100.10", 1080);

        InetSocketAddress relayAddr = client.resolveRelayAddress(bindAddr, actualRemote);

        assertEquals("198.51.100.10", relayAddr.getAddress().getHostAddress());
        assertEquals(53000, relayAddr.getPort());
    }

    @Test
    public void resolveRelayAddress_fallsBackToConfiguredProxyAddressWhenRemoteMissing() {
        Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:1080"));
        InetSocketAddress bindAddr = new InetSocketAddress("0.0.0.0", 53001);

        InetSocketAddress relayAddr = client.resolveRelayAddress(bindAddr, null);

        assertEquals("127.0.0.1", relayAddr.getAddress().getHostAddress());
        assertEquals(53001, relayAddr.getPort());
    }

    @Test
    public void resolveRelayAddress_keepsConcreteReplyAddress() {
        Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:1080"));
        InetSocketAddress bindAddr = new InetSocketAddress("203.0.113.9", 53002);

        InetSocketAddress relayAddr = client.resolveRelayAddress(bindAddr, new InetSocketAddress("198.51.100.20", 1080));

        assertSame(bindAddr, relayAddr);
    }
}
