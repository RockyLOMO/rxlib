package org.rx.net.dns;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.AbstractTester;
import org.rx.bean.RandomList;
import org.rx.core.Arrays;
import org.rx.core.Tasks;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.support.GeoManager;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.rx.core.Sys.toJsonString;

/**
 * DnsServer + DnsHandler 端到端：静态 hosts、拦截器缓存、解析完成后 {@link DnsServer#resolvingKeys} 应被清空。
 * 同时覆盖 addHosts(addOrUpdate)、removeHosts（不凭空建空列表）、negativeTtl 默认值。
 */
@Slf4j
public class DnsServerIntegrationTest extends AbstractTester {

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Slf4j
    static class MyContract implements SocksRpcContract {
        final InetAddress aopIp;

        public MyContract(InetAddress aopIp) {
            this.aopIp = aopIp;
        }

        @Override
        public void fakeEndpoint(BigInteger hash, String realEndpoint) {
        }

        @SneakyThrows
        @Override
        public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
            log.info("resolveHost {}", host);
            return Collections.singletonList(aopIp);
        }

        @Override
        public void addWhiteList(InetAddress endpoint) {
        }
    }

    @Test
    void removeHosts_unknownHost_doesNotCreateMapEntry() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
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
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
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
    void negativeTtl_defaultIs30() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            assertEquals(30, server.getNegativeTtl());
            server.setNegativeTtl(60);
            assertEquals(60, server.getNegativeTtl());
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(45)
    void udp_hostsRecord_returnsConfiguredAddress() throws Exception {
        int dnsPort = freePort();
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
        int dnsPort = freePort();
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
                assertEquals(1, resolveCalls.get());
                assertEquals(shadow, client.resolve(name));
                assertEquals(1, resolveCalls.get(), "缓存命中后不应再次 resolveHost");
                assertTrue(server.resolvingKeys.isEmpty(), "解析完成后 resolvingKeys 应 remove");
            }
        } finally {
            server.close();
        }
    }
    @SneakyThrows
    @Test
    public void dns() {
        String host_devops = "baidu.com";
        InetSocketAddress nsEp = Sockets.parseEndpoint("114.114.114.114:53");
        InetSocketAddress localNsEp = Sockets.parseEndpoint("127.0.0.1:853");

        final InetAddress ip2 = InetAddress.getByName("2.2.2.2");
        final InetAddress ip4 = InetAddress.getByName("4.4.4.4");
        final InetAddress aopIp = InetAddress.getByName("1.2.3.4");
        DnsServer server = new DnsServer(localNsEp.getPort(), Collections.singletonList(nsEp));
        try {
            server.setInterceptors(new RandomList<>(Collections.singletonList(new MyContract(aopIp))));
            server.setHostsTtl(5);
            server.setEnableHostsWeight(false);
            server.addHosts(host_devops, 2, Arrays.toList(ip2, ip4));

            //hostTtl
            DnsClient client = new DnsClient(Collections.singletonList(localNsEp));
            List<InetAddress> result = client.resolveAll(host_devops);
            System.out.println("eq: " + result);
            assert result.contains(ip2) && result.contains(ip4);
            Tasks.setTimeout(() -> {
                try {
                    server.removeHosts(host_devops, Collections.singletonList(ip2));

                    List<InetAddress> x = client.resolveAll(host_devops);
                    System.out.println(toJsonString(x));
                    assert x.contains(ip4);
                } finally {
                    _notify();
                }
            }, 6000);

            DnsClient inlandClient = DnsClient.inlandClient();
            InetAddress wanIp = InetAddress.getByName(GeoManager.INSTANCE.getPublicIp());
            List<InetAddress> currentIps = inlandClient.resolveAll(host_devops);
            System.out.println("ddns: " + wanIp + " = " + currentIps);
            //注入InetAddress.getAllByName()变更要查询的dnsServer的地址，支持非53端口
            Sockets.injectNameService(Collections.singletonList(localNsEp));

            List<InetAddress> wanResult = inlandClient.resolveAll(host_devops);
            InetAddress[] localResult = InetAddress.getAllByName(host_devops);
            System.out.println("wanResolve: " + wanResult + " != " + toJsonString(localResult));
            assert !wanResult.get(0).equals(localResult[0]);

            server.addHostsFile(path("hosts.txt"));
            assert client.resolve(host_cloud).equals(InetAddress.getByName("192.168.31.7"));

            assert client.resolve("www.baidu.com").equals(aopIp);

            _wait();
        } finally {
            server.close();
        }
    }
}
