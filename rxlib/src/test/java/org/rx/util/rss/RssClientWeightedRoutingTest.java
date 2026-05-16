package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.bean.RandomList;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 RSS 上游权重与源地址粘滞选择语义。
 */
class RssClientWeightedRoutingTest {
    private static final int SAMPLES = 5_000;

    @Test
    void weightOf_ReadsSocksServerWeightParameter() {
        AuthenticEndpoint primary = AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080?w=9");
        AuthenticEndpoint backup = AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081?w=1");

        assertEquals(9, RssClient.weightOf(primary));
        assertEquals(1, RssClient.weightOf(backup));
    }

    @Test
    void nextUpstream_RespectsWeightsAcrossDifferentSourceIps() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 9);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 1);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);

            int primaryHits = 0;
            int backupHits = 0;
            for (int i = 0; i < SAMPLES; i++) {
                UpstreamSupport selected = RssClient.nextUpstream(servers, sourceAddress(i));
                if (selected == primary) {
                    primaryHits++;
                } else if (selected == backup) {
                    backupHits++;
                }
            }

            assertEquals(SAMPLES, primaryHits + backupHits);
            double primaryRatio = primaryHits / (double) SAMPLES;
            assertTrue(primaryRatio >= 0.84D && primaryRatio <= 0.96D,
                    "不同源 IP 首次选择应接近 9:1，实际 primary=" + primaryHits
                            + ", backup=" + backupHits + ", ratio=" + primaryRatio);
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void nextUpstream_KeepsSameSourceIpPinnedWithinSteeringTtl() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 9);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 1);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
            InetAddress source = InetAddress.getByName("10.250.0.7");

            UpstreamSupport first = RssClient.nextUpstream(servers, source, null, true, 60);
            for (int i = 0; i < 200; i++) {
                assertSame(first, RssClient.nextUpstream(servers, source, null, true, 60),
                        "同源 IP 在 srcSteeringTTL 内应固定到首次选中的上游");
            }
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void nextUpstream_SkipsSourceSteeringForCommonStatelessPorts() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 1);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 0);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
            InetAddress source = InetAddress.getByName("10.250.0.8");
            UnresolvedEndpoint https = new UnresolvedEndpoint("example.com", 443);

            assertSame(primary, RssClient.nextUpstream(servers, source, https, true, 60));
            servers.setWeight(primary, 0);
            servers.setWeight(backup, 1);

            assertSame(backup, RssClient.nextUpstream(servers, source, https, true, 60),
                    "443 这类无状态常见端口不应被同源 IP 粘滞缓存固定");
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void nextUpstream_KeepsSourceSteeringForStatefulPorts() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 1);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 0);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
            InetAddress source = InetAddress.getByName("10.250.0.9");
            UnresolvedEndpoint ssh = new UnresolvedEndpoint("example.com", 22);

            assertSame(primary, RssClient.nextUpstream(servers, source, ssh, true, 60));
            servers.setWeight(primary, 0);
            servers.setWeight(backup, 1);

            assertSame(primary, RssClient.nextUpstream(servers, source, ssh, true, 60),
                    "非无状态端口仍应保留 srcSteeringTTL 粘滞缓存");
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void nextUpstream_DisablesSourceSteeringForClosingOldInstance() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 1);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 0);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
            InetAddress source = InetAddress.getByName("10.250.0.10");
            UnresolvedEndpoint ssh = new UnresolvedEndpoint("example.com", 22);

            assertSame(primary, RssClient.nextUpstream(servers, source, ssh, true, 60));
            servers.setWeight(primary, 0);
            servers.setWeight(backup, 1);

            assertSame(backup, RssClient.nextUpstream(servers, source, ssh, false, 60),
                    "热加载旧实例关闭 source steering 后不应继续命中同源 IP 粘滞缓存");
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void nextUpstream_FallsBackToWeightedSelectionWhenSourceIpMissing() throws Exception {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            UpstreamSupport primary = upstream("127.0.0.1", 1080, 1);
            UpstreamSupport backup = upstream("127.0.0.1", 1081, 0);
            RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
            UnresolvedEndpoint ssh = new UnresolvedEndpoint("example.com", 22);

            assertSame(primary, RssClient.nextUpstream(servers, null, ssh, true, 60));
            servers.setWeight(primary, 0);
            servers.setWeight(backup, 1);

            assertSame(backup, RssClient.nextUpstream(servers, null, ssh, true, 60),
                    "源 IP 缺失时不应用 null key 写入同源 IP 粘滞缓存");
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    void shadowRoutePlan_FallsBackToWeightedSelectionWhenSourceIpMissing() {
        UpstreamSupport primary = upstream("127.0.0.1", 1080, 1);
        UpstreamSupport backup = upstream("127.0.0.1", 1081, 0);
        RandomList<UpstreamSupport> servers = weightedServers(primary, backup);
        RssRuntime.ShadowRoutePlan plan = RssRuntime.ShadowRoutePlan.direct(servers);
        UnresolvedEndpoint ssh = new UnresolvedEndpoint("example.com", 22);

        assertSame(primary, plan.nextSupport(null, ssh, 60));
        servers.setWeight(primary, 0);
        servers.setWeight(backup, 1);

        assertSame(backup, plan.nextSupport(null, ssh, 60),
                "SS route plan 源 IP 缺失时不应使用同源 IP 粘滞缓存");
    }

    @Test
    void commonStatelessPortList_CoversHttpAndFrequentlyStatelessProtocols() {
        assertTrue(RssClient.isCommonStatelessPort(80));
        assertTrue(RssClient.isCommonStatelessPort(443));
        assertTrue(RssClient.isCommonStatelessPort(53));
        assertTrue(RssClient.isCommonStatelessPort(123));
        assertTrue(!RssClient.isCommonStatelessPort(22));
    }

    private static RandomList<UpstreamSupport> weightedServers(UpstreamSupport primary, UpstreamSupport backup) {
        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(primary, primary.getConfiguredWeight());
        servers.add(backup, backup.getConfiguredWeight());
        return servers;
    }

    private static UpstreamSupport upstream(String host, int port, int weight) {
        UpstreamSupport support = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress(host, port)), null);
        support.setConfiguredWeight(weight);
        return support;
    }

    private static InetAddress sourceAddress(int i) throws Exception {
        return InetAddress.getByAddress(new byte[]{
                10,
                (byte) ((i >>> 16) & 0xff),
                (byte) ((i >>> 8) & 0xff),
                (byte) (i & 0xff)
        });
    }
}
