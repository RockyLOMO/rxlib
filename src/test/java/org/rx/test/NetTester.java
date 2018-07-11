package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.socks.Sockets;
import org.rx.socks.proxy.ProxyServer;

public class NetTester {
    @Test
    public void testDirectServer() {
        ProxyServer server = new ProxyServer();
        server.start(8801, Sockets.parseAddress("127.0.0.1:8888"));
        System.out.println("server started...");
    }
}
