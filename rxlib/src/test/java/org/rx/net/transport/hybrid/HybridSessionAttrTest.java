package org.rx.net.transport.hybrid;

import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSessionAttrTest {
    @Test
    @Timeout(10)
    void attrStoredInSessionAndClearedOnClose() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

        AttributeKey<String> key = AttributeKey.valueOf("hybrid.attr.test");
        HybridSession session;
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            session = client.session();
            session.attr(key, "metadata");

            assertTrue(session.hasAttr(key));
            assertEquals("metadata", session.attr(key));
            session.attr(key, null);
            assertFalse(session.hasAttr(key));

            session.attr(key, "metadata");
        }
        assertFalse(session.hasAttr(key));
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
