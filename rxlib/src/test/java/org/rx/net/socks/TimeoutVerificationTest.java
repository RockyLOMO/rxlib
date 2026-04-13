package org.rx.net.socks;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.core.Reflects;
import static org.junit.jupiter.api.Assertions.*;

public class TimeoutVerificationTest {

    @Test
    void testAcceptChannel_WithZeroTimeout() {
        SocksConfig config = new SocksConfig(0);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        SocksProxyServer server = new SocksProxyServer(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        // Invoke private acceptChannel(Channel channel)
        Reflects.invokeMethod(server, "acceptChannel", channel);

        assertNull(channel.pipeline().get(ProxyChannelIdleHandler.class), "Should not add ProxyChannelIdleHandler when timeout is 0");
    }

    @Test
    void testAcceptChannel_WithReadTimeout() {
        SocksConfig config = new SocksConfig(0);
        config.setReadTimeoutSeconds(10);
        config.setWriteTimeoutSeconds(0);
        SocksProxyServer server = new SocksProxyServer(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        Reflects.invokeMethod(server, "acceptChannel", channel);

        assertNotNull(channel.pipeline().get(ProxyChannelIdleHandler.class), "Should add ProxyChannelIdleHandler when readTimeout is > 0");
    }

    @Test
    void testAcceptChannel_WithWriteTimeout() {
        SocksConfig config = new SocksConfig(0);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(10);
        SocksProxyServer server = new SocksProxyServer(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        Reflects.invokeMethod(server, "acceptChannel", channel);

        assertNotNull(channel.pipeline().get(ProxyChannelIdleHandler.class), "Should add ProxyChannelIdleHandler when writeTimeout is > 0");
    }
}
