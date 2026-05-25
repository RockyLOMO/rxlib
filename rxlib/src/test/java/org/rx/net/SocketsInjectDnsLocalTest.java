package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.rx.net.dns.DnsClient;
import org.rx.net.nameserver.NameserverClient;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class SocketsInjectDnsLocalTest {
    @Test
    public void injectNsFLiCnResolveSvcMercuryByJdkAndBootstrapDirect() throws Exception {
        assumeTrue(Boolean.getBoolean("rx.runExternalDnsTest"),
                "External DNS smoke test requires -Drx.runExternalDnsTest=true");

        boolean jdkOk = false;
        boolean bootstrapOk = false;
        boolean hybridOk = false;
        StringBuilder errors = new StringBuilder();
        NameserverClient client = new NameserverClient("codex-dns-smoke-" + UUID.randomUUID().toString());

        try {
            client.registerAsync("ns.f-li.cn:854").get(20, TimeUnit.SECONDS);
            client.waitInject(20 * 1000);
            System.out.println("nameserverDiscoveryEndpoints=" + client.discoveryEndpoints());
            System.out.println("directNameServers=" + DnsClient.directNameServers());

            try {
                List<InetAddress> jdk = Arrays.asList(InetAddress.getAllByName("svc-mercury"));
                System.out.println("jdkInjected=" + jdk);
                jdkOk = !jdk.isEmpty();
            } catch (Throwable e) {
                System.out.println("jdkInjectedError=" + e);
                errors.append("jdkInjected=").append(e).append('\n');
            }

            Bootstrap bootstrap = Sockets.bootstrap(new SocketConfig(), ch -> {
            });
            InetSocketAddress endpoint = Sockets.newUnresolvedEndpoint("svc-mercury", 1211);
            AddressResolver<InetSocketAddress> resolver = (AddressResolver<InetSocketAddress>)
                    bootstrap.config().resolver().getResolver(bootstrap.config().group().next());
            try {
                Future<InetSocketAddress> future = resolver.resolve(endpoint);
                InetSocketAddress resolved = future.get(10, TimeUnit.SECONDS);
                System.out.println("bootstrapDirect=" + resolved);
                bootstrapOk = !resolved.isUnresolved() && resolved.getPort() == 1211;
            } catch (Throwable e) {
                System.out.println("bootstrapDirectError=" + e);
                errors.append("bootstrapDirect=").append(e).append('\n');
            }

            HybridConfig hybridConfig = new HybridConfig();
            hybridConfig.getTcpClientConfig().setConnectTimeoutMillis(10 * 1000);
            hybridConfig.getTcpClientConfig().setEnableReconnect(false);
            hybridConfig.setEnableUdpDirect(false);
            hybridConfig.setEnableUdpHolePunch(false);
            try (HybridClient hybridClient = new HybridClient(hybridConfig)) {
                InetSocketAddress hybridEndpoint = Sockets.newUnresolvedEndpoint("svc-mercury", 1211);
                hybridClient.connect(hybridEndpoint);
                System.out.println("hybridConnect=" + hybridEndpoint
                        + ", connected=" + hybridClient.isConnected()
                        + ", sessionReady=" + hybridClient.isSessionReady()
                        + ", session=" + hybridClient.session());
                hybridOk = hybridClient.isConnected();
            } catch (Throwable e) {
                System.out.println("hybridConnectError=" + e);
                errors.append("hybridConnect=").append(e).append('\n');
            }
        } finally {
            try {
                client.deregisterAsync().get(5, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
            client.close();
            Sockets.injectNameService((srcIp, host) -> null);
            DnsClient.resetDirectClient();
        }

        assertTrue(jdkOk, "jdk injected dns result must not be empty\n" + errors);
        assertTrue(bootstrapOk, "bootstrap direct dns result must be resolved and keep port\n" + errors);
        assertTrue(hybridOk, "hybrid client must connect svc-mercury:1211\n" + errors);
    }
}
