package org.rx.net;

import io.netty.channel.local.LocalAddress;
import io.netty.channel.epoll.Epoll;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketsReusePortTest {
    @Test
    void reusePortBindCount_requiresFixedInetPortAndRespectsConfig() {
        SocketConfig config = new SocketConfig();
        assertEquals(-1, config.getReusePortBindCount());
        config.setReusePortBindCount(4);
        int expectedReuseBindCount = Epoll.isAvailable() ? 4 : 1;

        assertEquals(expectedReuseBindCount, Sockets.reusePortBindCount(config, new InetSocketAddress("127.0.0.1", 18080)));
        config.setReusePortBindCount(0);
        int expectedRecommendedBindCount = Epoll.isAvailable() ? 8 : 1;
        assertEquals(expectedRecommendedBindCount, Sockets.reusePortBindCount(config, new InetSocketAddress("127.0.0.1", 18080)));
        assertEquals(1, Sockets.reusePortBindCount(config, new InetSocketAddress("127.0.0.1", 0)));
        assertEquals(1, Sockets.reusePortBindCount(config, new LocalAddress("rss-in")));
    }

    @Test
    void reusePortBindCount_fallsBackToGlobalOnlyWhenConfigUnset() {
        int oldBindCount = RxConfig.INSTANCE.getNet().getReusePortBindCount();
        try {
            RxConfig.INSTANCE.getNet().setReusePortBindCount(4);
            SocketConfig config = new SocketConfig();
            int expectedGlobalBindCount = Epoll.isAvailable() ? 4 : 1;
            assertEquals(expectedGlobalBindCount, Sockets.reusePortBindCount(config, new InetSocketAddress("127.0.0.1", 18080)));

            config.setReusePortBindCount(1);
            assertEquals(1, Sockets.reusePortBindCount(config, new InetSocketAddress("127.0.0.1", 18080)));
        } finally {
            RxConfig.INSTANCE.getNet().setReusePortBindCount(oldBindCount);
        }
    }
}
