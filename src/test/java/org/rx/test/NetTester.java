package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.common.App;
import org.rx.socks.Sockets;
import org.rx.socks.proxy.ProxyServer;

import static org.rx.socks.proxy.ProxyServer.Compression_Key;

public class NetTester {
    @Test
    @SneakyThrows
    public void testDirectServer() {
        ProxyServer server = new ProxyServer();
        server.start(8801, Sockets.parseAddress("127.0.0.1:8888"));
        System.out.println("server started...");
        System.in.read();
    }

    @Test
    public void testSettings() {
        System.out.println(App.convert(App.readSetting(Compression_Key), boolean.class));
    }
}
