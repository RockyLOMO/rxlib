package org.rx.net;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.rx.net.dns.DnsClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Disabled("Local DNS smoke test: requires 192.168.31.4:753 to resolve svc-mercury")
public class SocketsInjectDnsLocalTest {
    @Test
    public void injectDnsResolveSvcMercury() throws Exception {
        List<InetSocketAddress> nameServers = Collections.singletonList(
                new InetSocketAddress(InetAddress.getByName("192.168.31.4"), 753));

        try (DnsClient client = new DnsClient(nameServers, false)) {
            List<InetAddress> direct = client.resolveAll("svc-mercury");
            System.out.println("direct=" + direct);
            assertFalse(direct.isEmpty(), "direct dns result must not be empty");
        }

        Sockets.injectNameService(nameServers);
        List<InetAddress> injected = Arrays.asList(InetAddress.getAllByName("svc-mercury"));
        System.out.println("injected=" + injected);
        assertFalse(injected.isEmpty(), "injected dns result must not be empty");
    }
}
