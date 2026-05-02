package org.rx.net.dns;

import io.netty.channel.Channel;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.AbstractTester;
import org.rx.bean.RandomList;
import org.rx.core.Arrays;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DnsServer + DnsHandler 端到端：静态 hosts、拦截器缓存、并发解析合并。
 * 同时覆盖 addHosts(addOrUpdate)、removeHosts（不凭空建空列表）、negativeTtl 默认值。
 */
@Slf4j
public class DnsServerIntegrationTest extends AbstractTester {

    static int freeDnsPort() throws Exception {
        for (int i = 0; i < 16; i++) {
            try (ServerSocket tcp = new ServerSocket(0)) {
                int port = tcp.getLocalPort();
                try (DatagramSocket udp = new DatagramSocket(port)) {
                    return port;
                }
            } catch (Exception e) {
                // DNS server binds TCP and UDP on the same port; retry if either side is busy.
            }
        }
        throw new IllegalStateException("No free TCP/UDP DNS port");
    }

    @Slf4j
    static class MyContract implements SocksRpcContract {
        final InetAddress aopIp;

        public MyContract(InetAddress aopIp) {
            this.aopIp = aopIp;
        }

        @Override
        public void fakeEndpoint(long hash, String realEndpoint, String token) {
        }

        @SneakyThrows
        @Override
        public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
            log.info("resolveHost {}", host);
            return Collections.singletonList(aopIp);
        }

        @Override
        public void addWhiteList(InetAddress endpoint, String token) {
        }
    }

    @Test
    void setInterceptors_empty_clearsResolverState() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            server.setInterceptors(new RandomList<DnsServer.ResolveInterceptor>(Collections.emptyList()));
            assertNull(server.interceptors);
            assertNull(server.interceptorCache);
        } finally {
            server.close();
        }
    }

    @Test
    void close_releasesChannelsAndUpstreamResolver() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());

        server.close();

        assertTrue(server.tcpChannels.stream().noneMatch(Channel::isOpen));
        assertTrue(server.udpChannels.stream().noneMatch(Channel::isOpen));
        assertTrue(server.upstreamClient.isClosed());
    }

    @Test
    void removeHosts_unknownHost_doesNotCreateMapEntry() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            InetAddress lo = InetAddress.getLoopbackAddress();
            assertFalse(server.removeHosts("nonexistent.example", Collections.singletonList(lo)));
            assertNull(server.getHosts().get("nonexistent.example"), "removeHosts 不应创建空 RandomList 条目");
            assertTrue(server.getHosts("nonexistent.example").isEmpty());
        } finally {
            server.close();
        }
    }

    @Test
    void addHosts_addOrUpdate_sameIp_updatesWeight() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            InetAddress lo = InetAddress.getLoopbackAddress();
            String host = "w.example";
            assertTrue(server.addHosts(host, 2, Collections.singletonList(lo)));
            assertFalse(server.addHosts(host, 9, Collections.singletonList(lo)), "仅更新权重时整体可无新增");
            assertEquals(1, server.getAllHosts(host).size());
            assertEquals(lo, server.getAllHosts(host).get(0));
            RandomList<InetAddress> rl = server.getHosts().get(host);
            assertNotNull(rl);
            assertEquals(9, rl.getWeight(lo));
        } finally {
            server.close();
        }
    }

    @Test
    void domainShouldBeCaseInsensitive() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            assertTrue(server.addHosts("Example.COM.", "127.0.0.1"));

            assertEquals(lo, server.getHosts("example.com").get(0));
            assertEquals(lo, server.getHosts("EXAMPLE.COM.").get(0));
            assertTrue(server.getHosts().containsKey("example.com"));
        } finally {
            server.close();
        }
    }

    @Test
    void negativeTtl_defaultIs5() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            assertEquals(DnsServer.DEFAULT_NEGATIVE_TTL, server.getNegativeTtl());
            server.setNegativeTtl(60);
            assertEquals(60, server.getNegativeTtl());
        } finally {
            server.close();
        }
    }

    @Test
    void addHostsFile_missingFile_skips() throws Exception {
        DnsServer server = new DnsServer(freeDnsPort(), Collections.emptyList());
        try {
            String missing = Paths.get("target", "missing-hosts-" + UUID.randomUUID() + ".txt").toString();
            assertDoesNotThrow(() -> server.addHostsFile(missing));
            assertTrue(server.getHosts().isEmpty());
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(45)
    void udp_hostsRecord_returnsConfiguredAddress() throws Exception {
        int dnsPort = freeDnsPort();
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        String name = "it-hosts-only.example";
        DnsServer server = new DnsServer(dnsPort, Collections.emptyList());
        try {
            assertTrue(server.addHosts(name, "127.0.0.1"));
            Thread.sleep(400);
            try (DnsClient client = new DnsClient(Collections.singletonList(new InetSocketAddress("127.0.0.1", dnsPort)))) {
                InetAddress r = client.resolve(name);
                assertEquals(loop, r);
            }
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(60)
    void interceptor_secondQueryUsesCache_singleResolveHost() throws Exception {
        int dnsPort = freeDnsPort();
        InetAddress shadow = InetAddress.getByName("198.51.100.77");
        String name = "it-cache-" + UUID.randomUUID() + ".example";
        AtomicInteger resolveCalls = new AtomicInteger();
        // 空上游列表 -> DnsClient 使用平台 DNS；本用例仅走拦截器 + H2 缓存，不依赖假上游
        DnsServer server = new DnsServer(dnsPort, Collections.emptyList());
        try {
            server.setInterceptors(new RandomList<>(Collections.singletonList((srcIp, host) -> {
                if (name.equals(host)) {
                    resolveCalls.incrementAndGet();
                    return Collections.singletonList(shadow);
                }
                return null;
            })));
            Thread.sleep(400);
            try (DnsClient client = new DnsClient(Collections.singletonList(new InetSocketAddress("127.0.0.1", dnsPort)))) {
                assertEquals(shadow, client.resolve(name));
                assertEquals(2, resolveCalls.get(), "A/AAAA 应分别写入缓存");
                assertEquals(shadow, client.resolve(name));
                assertEquals(2, resolveCalls.get(), "缓存命中后不应再次 resolveHost");
                assertTrue(server.resolvingPromises.isEmpty(), "解析完成后 resolvingPromises 应 remove");
            }
        } finally {
            server.close();
        }
    }
    @SneakyThrows
    @Test
    public void dns() {
        String host_devops = "dns-it-" + UUID.randomUUID() + ".example";
        String injectedHost = "dns-injected-" + UUID.randomUUID() + ".example";
        int dnsPort = freeDnsPort();
        InetSocketAddress localNsEp = Sockets.parseEndpoint("127.0.0.1:" + dnsPort);

        final InetAddress ip2 = InetAddress.getByName("2.2.2.2");
        final InetAddress ip4 = InetAddress.getByName("4.4.4.4");
        final InetAddress aopIp = InetAddress.getByName("1.2.3.4");
        DnsServer server = new DnsServer(localNsEp.getPort(), Collections.emptyList());
        try (DnsClient client = new DnsClient(Collections.singletonList(localNsEp))) {
            server.setInterceptors(new RandomList<>(Collections.singletonList(new MyContract(aopIp))));
            server.setHostsTtl(5);
            server.setEnableHostsWeight(false);
            server.addHosts(host_devops, 2, Arrays.toList(ip2, ip4));
            server.addHosts(injectedHost, 1, Collections.singletonList(ip4));

            //hostTtl
            List<InetAddress> result = client.resolveAll(host_devops);
            System.out.println("eq: " + result);
            assertTrue(result.contains(ip2) && result.contains(ip4));

            Thread.sleep(6000);
            server.removeHosts(host_devops, Collections.singletonList(ip2));
            List<InetAddress> x = client.resolveAll(host_devops);
            assertTrue(x.contains(ip4));

            //注入InetAddress.getAllByName()变更要查询的dnsServer的地址，支持非53端口
            Sockets.injectNameService(Collections.singletonList(localNsEp));

            InetAddress[] localResult = InetAddress.getAllByName(injectedHost);
            assertEquals(ip4, localResult[0]);

            java.nio.file.Path hostsFile = Paths.get("target", "hosts-" + UUID.randomUUID() + ".txt");
            java.nio.file.Files.write(hostsFile,
                    Collections.singletonList("192.168.31.7 " + host_cloud), StandardCharsets.US_ASCII);
            server.addHostsFile(hostsFile.toString());
            assertEquals(InetAddress.getByName("192.168.31.7"), client.resolve(host_cloud));

            assertEquals(aopIp, client.resolve("www.baidu.com"));
        } finally {
            Sockets.injectNameService((srcIp, host) -> null);
            server.close();
        }
    }
}
