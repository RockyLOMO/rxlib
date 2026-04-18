package org.rx;

import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.*;

public class MainTest {
    @Test
    public void testResolveClientInListenAddress_DefaultToLocalAddress() {
        Main.RSSConf conf = new Main.RSSConf();

        SocketAddress address = Main.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertEquals(new LocalAddress("rss-in-6885"), address);
    }

    @Test
    public void testResolveClientInListenAddress_BindPortUsesLoopback() {
        Main.RSSConf conf = new Main.RSSConf();
        conf.socksBindPort = true;

        SocketAddress address = Main.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertTrue(address instanceof InetSocketAddress);
        InetSocketAddress endpoint = (InetSocketAddress) address;
        assertEquals(6885, endpoint.getPort());
        assertNotNull(endpoint.getAddress());
        assertTrue(endpoint.getAddress().isLoopbackAddress());
    }
}
