package org.rx;

import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;
import org.rx.util.rss.RSSConf;
import org.rx.util.rss.RssClient;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.*;

public class MainTest {
    @Test
    public void testResolveClientInListenAddress_DefaultToLocalAddress() {
        RSSConf conf = new RSSConf();

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertEquals(new LocalAddress("rss-in-6885"), address);
    }

    @Test
    public void testResolveClientInListenAddress_BindPortUsesLoopback() {
        RSSConf conf = new RSSConf();
        conf.socksBindPort = true;

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertTrue(address instanceof InetSocketAddress);
        InetSocketAddress endpoint = (InetSocketAddress) address;
        assertEquals(6885, endpoint.getPort());
        assertNotNull(endpoint.getAddress());
        assertTrue(endpoint.getAddress().isLoopbackAddress());
    }
}
