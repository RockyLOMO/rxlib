package org.rx.util.rss;

import com.alibaba.fastjson2.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Disabled;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.bean.DateTime;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.core.YamlConfiguration;
import org.rx.exception.InvalidException;
import org.rx.io.EntityDatabaseImpl;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.OptimalSettings;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.dns.DnsServer;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.SocksAuthenticator;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.function.TripleAction;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Sys.toJsonString;

public class RssTest extends AbstractTester {
    final int connectTimeoutMillis = 30000;
    final String socks5Usr = "rocky";
    final String socks5Pwd = "123456";
    final CopyOnWriteArraySet<String> bypassHosts = new CopyOnWriteArraySet<String>(RxConfig.INSTANCE.getNet().getBypassHosts()) {
        {
            add("*qq*");
        }
    };

    @Test
    public void resolveClientInListenAddress_DefaultToLocalAddress() {
        RSSConf conf = new RSSConf();

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertEquals(new LocalAddress("rss-in-6885"), address);
    }

    @Test
    public void resolveClientInListenAddress_BindPortUsesLoopback() {
        RSSConf conf = new RSSConf();
        conf.socksBindPort = true;

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertTrue(address instanceof InetSocketAddress);
        InetSocketAddress endpoint = (InetSocketAddress) address;
        assertEquals(6885, endpoint.getPort());
        assertNotNull(endpoint.getAddress());
        assertTrue(endpoint.getAddress().isLoopbackAddress());
    }

    @Test
    public void resolveNameserverConfig_UsesShadowDnsServerPortAndTtl() {
        RSSConf conf = new RSSConf();
        NameserverConfig config = new NameserverConfig();
        config.setRegisterPort(1854);
        conf.nameserver = config;
        conf.shadowDnsPort = 1853;
        conf.dnsTtlMinutes = 2;

        NameserverConfig resolved = RssClient.resolveNameserverConfig(conf);

        assertSame(config, resolved);
        assertEquals(1853, resolved.getDnsPort());
        assertEquals(120, resolved.getDnsTtl());
        assertEquals(1854, resolved.getRegisterPort());
    }

    @SneakyThrows
    @Test
    public void yamlConf() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration("output.yaml");
        RSSConf rssConf = yamlConfiguration.readAs(null, RSSConf.class);
        System.out.println("反序列化rssConf: " + rssConf);
        System.out.println("序列化rssConf:" + yamlConfiguration.dump());
    }

    @SneakyThrows
    @Test
    public void shadowUserJson() {
        ShadowUser user = new ShadowUser();
        user.setUsername("r");
        user.setSocksUser("inner-r");
        user.setLastResetTime(DateTime.now());
        user.getLoginIps().put(java.net.InetAddress.getByName("18.12.3.4"), new TrafficLoginInfo());
        System.out.println(toJsonString(RssClient.toShadowUserPayload(user)));
    }

    @SneakyThrows
    @Test
    public void renderShadowUsersPage_RendersShadowUserDetails() {
        RssClientHttpHandler.GeoLookup oldLookup = RssClientHttpHandler.geoLookup;
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSocksUser("inner-rocky");
        user.setSsPort(8388);
        user.setIpLimit(3);
        user.setLastResetTime(DateTime.valueOf("2026-04-24 12:34:56"));

        TrafficLoginInfo loginInfo = new TrafficLoginInfo();
        loginInfo.setLatestTime(DateTime.valueOf("2026-04-24 08:00:00"));
        loginInfo.getRefCnt().set(2);
        loginInfo.getTotalActiveSeconds().set(3600);
        loginInfo.getTotalReadBytes().set(2048);
        loginInfo.getTotalWriteBytes().set(4096);
        loginInfo.getTotalReadPackets().set(12);
        loginInfo.getTotalWritePackets().set(24);
        user.getLoginIps().put(InetAddress.getByName("18.12.3.4"), loginInfo);

        try {
            RssClientHttpHandler.geoLookup = new RssClientHttpHandler.GeoLookup() {
                @Override
                public IpGeolocation resolve(String ip) {
                    return new IpGeolocation("United States", "US", "New York", "US");
                }
            };

            String html = RssClientHttpHandler.renderShadowUsersPage(Collections.singletonMap(user.getUsername(), user));

            assertTrue(html.contains("RSS SS 用户信息"));
            assertTrue(html.contains(RssClientHttpHandler.SHADOW_USERS_PAGE_PATH));
            assertTrue(html.contains("ss-rocky"));
            assertTrue(html.contains("inner-rocky"));
            assertTrue(html.contains("18.12.3.4"));
            assertTrue(html.contains("United States / New York (US)"));
            assertTrue(html.contains("2.0KB"));
            assertTrue(html.contains("4.0KB"));
            assertTrue(!html.contains("下行包数"));
            assertTrue(!html.contains("上行包数"));
            assertTrue(!html.contains("历史包数"));
        } finally {
            RssClientHttpHandler.geoLookup = oldLookup;
        }
    }

    @Test
    public void renderShadowUsersPage_EmptyStoreShowsEmptyState() {
        String html = RssClientHttpHandler.renderShadowUsersPage(Collections.<String, ShadowUser>emptyMap());

        assertTrue(html.contains("暂无 SS 用户"));
        assertTrue(html.contains(RssClientHttpHandler.SHADOW_USERS_PAGE_PATH));
    }

    @Test
    public void liveActiveSeconds_UsesCurrentOnlineWindowForActiveConnections() {
        TrafficLoginInfo info = new TrafficLoginInfo();
        long now = System.currentTimeMillis();
        info.getTotalActiveSeconds().set(TimeUnit.HOURS.toSeconds(10));
        info.getRefCnt().set(3);
        info.getActiveSinceMillis().set(now - TimeUnit.HOURS.toMillis(3));

        assertEquals(TimeUnit.HOURS.toSeconds(3), RssClientHttpHandler.liveActiveSeconds(info, now));

        info.getRefCnt().set(0);
        info.getActiveSinceMillis().set(0L);
        assertEquals(TimeUnit.HOURS.toSeconds(10), RssClientHttpHandler.liveActiveSeconds(info, now));
    }

    @Test
    public void rssClientHttpHandler_RejectsMissingAuthorizationLikeDiagnosticPage() {
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        try {
            RxConfig.INSTANCE.setRtoken("test-rtoken");
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);
            ServerResponse response = new ServerResponse();

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.getStatus());
            assertEquals("Basic realm=\"rxlib-diagnostic\", charset=\"UTF-8\"",
                    response.getHeaders().get(HttpHeaderNames.WWW_AUTHENTICATE));
        } finally {
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void rssClientHttpHandler_ReturnsServiceUnavailableWhenRtokenEmpty() {
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        try {
            RxConfig.INSTANCE.setRtoken(null);
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);
            ServerResponse response = new ServerResponse();

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.getStatus());
            assertTrue(response.getContent().toString(StandardCharsets.UTF_8).contains("app.rtoken"));
        } finally {
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void rssClientHttpHandler_AllowsAuthorizedRequest() {
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        try {
            RxConfig.INSTANCE.setRtoken("test-rtoken");
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);
            String auth = Base64.getEncoder().encodeToString("rxlib:test-rtoken".getBytes(StandardCharsets.UTF_8));
            request.getHeaders().set(HttpHeaderNames.AUTHORIZATION, "Basic " + auth);
            ServerResponse response = new ServerResponse();

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.OK, response.getStatus());
            assertTrue(response.getContent().toString(StandardCharsets.UTF_8).contains("RSS SS 用户信息"));
        } finally {
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void rssUserTrafficStore_QueryByProtocolAndCleanupExpired() {
        EntityDatabaseImpl db = new EntityDatabaseImpl("jdbc:h2:mem:rss_store_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL", null, 1, true);
        RssUserTrafficStore store = new RssUserTrafficStore(db);
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSocksUser("inner-rocky");
        try {
            InetSocketAddress remote = new InetSocketAddress("18.12.3.4", 12345);
            store.record(user, remote, SocksUserTraffic.PROTOCOL_TCP, 1024L, 2048L, 10L, 20L);
            store.recordSession(user, remote, SocksUserTraffic.PROTOCOL_TCP, 60L);
            store.record(user, remote, SocksUserTraffic.PROTOCOL_UDP, 512L, 256L, 5L, 3L);
            store.flush();

            List<RssUserTrafficStore.UserTrafficSummary> users = store.queryUserSummaries(0L, System.currentTimeMillis());
            assertEquals(1, users.size());
            assertEquals(1536L, users.get(0).getReadBytes());
            assertEquals(2304L, users.get(0).getWriteBytes());

            List<RssUserTrafficStore.ProtocolTrafficSummary> protocols = store.queryProtocolSummaries(0L, System.currentTimeMillis());
            assertEquals(2, protocols.size());
            assertTrue(protocols.stream().anyMatch(p -> SocksUserTraffic.PROTOCOL_TCP.equals(p.getProtocol()) && p.getSessionCount() == 1L));
            assertTrue(protocols.stream().anyMatch(p -> SocksUserTraffic.PROTOCOL_UDP.equals(p.getProtocol()) && p.getReadBytes() == 512L));

            List<RssUserTrafficStore.LoginIpTrafficSummary> loginIps = store.queryLoginIpSummaries(0L, System.currentTimeMillis());
            assertEquals(2, loginIps.size());

            RssUserTrafficStore.HourlyTrafficEntity oldTraffic = new RssUserTrafficStore.HourlyTrafficEntity();
            long expiredHour = System.currentTimeMillis() / RssUserTrafficStore.ONE_HOUR_MILLIS - (RssUserTrafficStore.DEFAULT_RETENTION_DAYS * 24L + 1L);
            oldTraffic.setId(RssUserTrafficStore.HourlyTrafficEntity.idOf("expired", expiredHour));
            oldTraffic.setUsername("expired");
            oldTraffic.setHourEpoch(expiredHour);
            oldTraffic.setCreateTime(new Date());
            oldTraffic.setModifyTime(new Date());
            db.save(oldTraffic, true);

            RssUserTrafficStore.HourlyLoginIpTrafficEntity oldIp = new RssUserTrafficStore.HourlyLoginIpTrafficEntity();
            oldIp.setId(RssUserTrafficStore.HourlyLoginIpTrafficEntity.idOf("expired", "1.1.1.1", SocksUserTraffic.PROTOCOL_TCP, expiredHour));
            oldIp.setUsername("expired");
            oldIp.setRemoteIp("1.1.1.1");
            oldIp.setProtocol(SocksUserTraffic.PROTOCOL_TCP);
            oldIp.setHourEpoch(expiredHour);
            oldIp.setCreateTime(new Date());
            oldIp.setModifyTime(new Date());
            db.save(oldIp, true);

            store.cleanupExpired();
            assertEquals(0L, db.count(new org.rx.io.EntityQueryLambda<RssUserTrafficStore.HourlyTrafficEntity>(RssUserTrafficStore.HourlyTrafficEntity.class)
                    .eq(RssUserTrafficStore.HourlyTrafficEntity::getUsername, "expired")));
            assertEquals(0L, db.count(new org.rx.io.EntityQueryLambda<RssUserTrafficStore.HourlyLoginIpTrafficEntity>(RssUserTrafficStore.HourlyLoginIpTrafficEntity.class)
                    .eq(RssUserTrafficStore.HourlyLoginIpTrafficEntity::getUsername, "expired")));
        } finally {
            db.close();
        }
    }

    @Test
    public void renderShadowUsersPage_RendersH2HistoryAndRealtimeSections() {
        EntityDatabaseImpl db = new EntityDatabaseImpl("jdbc:h2:mem:rss_page_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL", null, 1, true);
        RssUserTrafficStore store = new RssUserTrafficStore(db);
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSocksUser("inner-rocky");
        user.setSsPort(8388);
        user.setLastResetTime(DateTime.valueOf("2026-04-24 12:34:56"));
        TrafficLoginInfo loginInfo = new TrafficLoginInfo();
        loginInfo.setLatestTime(DateTime.valueOf("2026-04-24 08:00:00"));
        loginInfo.getRefCnt().set(2);
        loginInfo.getTotalActiveSeconds().set(3600);
        loginInfo.getTotalReadBytes().set(2048);
        loginInfo.getTotalWriteBytes().set(4096);
        user.getLoginIps().put(InetAddress.getLoopbackAddress(), loginInfo);
        try {
            store.record(user, new InetSocketAddress("18.12.3.4", 12345), SocksUserTraffic.PROTOCOL_TCP, 1024L, 2048L, 10L, 20L);
            store.recordSession(user, new InetSocketAddress("18.12.3.4", 12345), SocksUserTraffic.PROTOCOL_TCP, 60L);
            store.flush();

            RssClientHttpHandler.Query query = new RssClientHttpHandler.Query();
            query.fromMillis = 0L;
            query.toMillis = System.currentTimeMillis();
            query.fromValue = "1970-01-01T00:00";
            query.toValue = "2099-01-01T00:00";
            String html = RssClientHttpHandler.renderShadowUsersPage(Collections.singletonMap(user.getUsername(), user), store, query);

            assertTrue(html.contains("用户历史概览"));
            assertTrue(html.contains("协议历史明细"));
            assertTrue(html.contains("实时内存快照"));
            assertTrue(html.contains("TCP"));
            assertTrue(html.contains("ss-rocky"));
            assertTrue(html.contains("18.12.3.4"));
        } finally {
            db.close();
        }
    }

    @Test
    public void optimalSettings() {
        OptimalSettings[] a = {RssSupport.SS_IN_OPS, RssSupport.OUT_OPS};
        for (OptimalSettings ops : a) {
            ops.calculate();
            System.out.println(ops);
        }
    }

    @Test
    public void resolveShadowEndpoint_TunFallsBackToSocksServerWithoutUdp2raw() {
        SocketAddress inSvrAddress = new LocalAddress("rss-in-6885");
        AuthenticEndpoint endpoint = RssClient.resolveShadowEndpoint(inSvrAddress, null, null, "ss-user", "tun-a");

        assertSame(inSvrAddress, endpoint.getEndpoint());
        assertEquals("ss-user", endpoint.getParameters().get(org.rx.net.socks.SocksConnectionTagRegistry.PARAM_NAME));
    }

    @Test
    public void shouldScheduleDdns_RequiresPositivePeriodAndDomains() {
        RSSConf conf = new RSSConf();

        assertTrue(!RssClient.shouldScheduleDdns(conf));
        conf.ddnsDomains = Collections.singletonList("a.example.com");
        assertTrue(!RssClient.shouldScheduleDdns(conf));
        conf.ddnsJobSeconds = 60;
        assertTrue(RssClient.shouldScheduleDdns(conf));
    }

    @Test
    public void applyUdpLeasePool_EnablesAndClampsRssConfig() {
        RSSConf conf = new RSSConf();
        conf.udpLeasePoolMinSize = 64;
        conf.udpLeasePoolMaxSize = 4;
        conf.udpLeasePoolMaxIdleMillis = 5;
        conf.udpLeaseRpcBreakerThreshold = 0;
        conf.udpLeaseRpcBreakerOpenSeconds = 0;
        SocksConfig config = new SocksConfig();

        RssClient.applyUdpLeasePool(conf, config);

        assertTrue(config.isUdpLeasePoolEnabled());
        assertEquals(4, config.getUdpLeasePoolMinSize());
        assertEquals(4, config.getUdpLeasePoolMaxSize());
        assertEquals(1000, config.getUdpLeasePoolMaxIdleMillis());
        assertEquals(1, config.getUdpLeaseRpcBreakerThreshold());
        assertEquals(1, config.getUdpLeaseRpcBreakerOpenSeconds());
    }

    @Test
    public void applyUdpLeasePool_DisabledKeepsPoolOff() {
        RSSConf conf = new RSSConf();
        conf.udpLeasePoolEnabled = false;
        SocksConfig config = new SocksConfig();
        config.setUdpLeasePoolEnabled(true);

        RssClient.applyUdpLeasePool(conf, config);

        assertTrue(!config.isUdpLeasePoolEnabled());
    }

    @Test
    public void routeUpstream_KcptunReturnsIndependentSupportWithSelectedFacade() {
        SocksConfig config = new SocksConfig();
        AuthenticEndpoint kcp = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 4093), "k", "p");
        AuthenticEndpoint upstream = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1090), "u", "p");
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public void fakeEndpoint(long hash, String realEndpoint) {
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        UpstreamSupport next = new UpstreamSupport(upstream, facade);
        config.setKcptunClient(kcp);

        UpstreamSupport routed = RssClient.routeUpstream(config, next);

        assertTrue(routed != next);
        assertSame(kcp, routed.getEndpoint());
        assertSame(facade, routed.getFacade());
    }

    @Test
    public void socksTcpUpstream_FakeEndpointHashIncludesSupportEndpoint() {
        final AtomicInteger calls = new AtomicInteger();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public void fakeEndpoint(long hash, String realEndpoint) {
                calls.incrementAndGet();
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        SocksConfig config = new SocksConfig();
        String dstHost = "fake-key-" + System.nanoTime() + ".example";
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(dstHost, 443);
        UpstreamSupport routedA = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);
        UpstreamSupport routedB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.101", 4093)), facade);

        UnresolvedEndpoint fakeA = new SocksTcpUpstream(dstEp, config, routedA).prepareDestination();
        UnresolvedEndpoint fakeB = new SocksTcpUpstream(dstEp, config, routedB).prepareDestination();

        assertNotEquals(fakeA.getHost(), fakeB.getHost());
        assertTrue(fakeA.getHost().endsWith(SocksRpcContract.FAKE_HOST_SUFFIX));
        assertTrue(fakeB.getHost().endsWith(SocksRpcContract.FAKE_HOST_SUFFIX));
        assertEquals(2, calls.get());
    }

    @Test
    @SneakyThrows
    public void nextUpstream_ThrowsWhenNoServerAvailable() {
        RSSConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RSSConf();
            RssClient.rssConf.route = new RSSConf.RouteConf();

            assertThrows(InvalidException.class,
                    () -> RssClient.nextUpstream(new RandomList<UpstreamSupport>(), java.net.InetAddress.getByName("127.0.0.1")));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    @SneakyThrows
    public void nextUpstream_SkipsUnhealthyCachedServer() {
        RSSConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RSSConf();
            RssClient.rssConf.route = new RSSConf.RouteConf();
            RssClient.rssConf.route.srcSteeringTTL = 60;
            RandomList<UpstreamSupport> servers = new RandomList<>();
            UpstreamSupport first = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), null);
            first.setConfiguredWeight(1);
            servers.add(first, 1);
            InetAddress source = InetAddress.getByName("127.0.0.1");

            assertSame(first, RssClient.nextUpstream(servers, source));

            UpstreamSupport second = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1081)), null);
            second.setConfiguredWeight(1);
            servers.add(second, 1);
            first.setHealthy(false);
            servers.setWeight(first, 0);

            assertSame(second, RssClient.nextUpstream(servers, source));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void routeUpstream_KcptunTracksSelectedServerConnections() {
        SocksConfig config = new SocksConfig();
        AuthenticEndpoint kcp = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 4093), "k", "p");
        AuthenticEndpoint upstream = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1090), "u", "p");
        UpstreamSupport next = new UpstreamSupport(upstream, null);
        config.setKcptunClient(kcp);

        UpstreamSupport routed = RssClient.routeUpstream(config, next);
        routed.retainConnection();
        try {
            assertSame(next, routed.getConnectionTracker());
            assertEquals(1, next.activeConnectionCount());
        } finally {
            routed.releaseConnection();
        }
        assertEquals(0, next.activeConnectionCount());
    }

    @Test
    public void closeUpstreamsWhenIdle_WaitsForActiveConnections() {
        AtomicInteger closeCount = new AtomicInteger();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public void fakeEndpoint(long hash, String realEndpoint) {
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }

            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        };
        UpstreamSupport support = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), facade);
        support.setConfiguredWeight(1);
        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(support, 1);
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<UpstreamSupport>(),
                new RandomList<DnsServer.ResolveInterceptor>());

        support.retainConnection();
        RssClient.closeUpstreamsWhenIdle(snapshot);
        assertEquals(0, closeCount.get());
        assertTrue(!snapshot.closed);

        support.releaseConnection();
        RssClient.closeUpstreamsWhenIdle(snapshot);
        assertEquals(1, closeCount.get());
        assertTrue(snapshot.closed);
    }

    @Test
    public void closeUpstreamsWhenIdle_ForceClosesAfterDeadline() {
        AtomicInteger closeCount = new AtomicInteger();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public void fakeEndpoint(long hash, String realEndpoint) {
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }

            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        };
        UpstreamSupport support = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), facade);
        support.setConfiguredWeight(1);
        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(support, 1);
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<UpstreamSupport>(),
                new RandomList<DnsServer.ResolveInterceptor>());

        support.retainConnection();
        snapshot.closeDeadlineMillis = System.currentTimeMillis() - 1L;
        try {
            RssClient.closeUpstreamsWhenIdle(snapshot);
            assertEquals(1, closeCount.get());
            assertTrue(snapshot.closed);
        } finally {
            support.releaseConnection();
        }
    }

    @Test
    public void upstreamCloseMaxWait_IsOneMinute() {
        assertEquals(TimeUnit.MINUTES.toMillis(1), RssClient.UPSTREAM_CLOSE_MAX_WAIT_MILLIS);
    }

    @Test
    public void renderShadowUsersPage_RendersUpstreamHealth() {
        UpstreamSupport support = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "secret"), null);
        support.setConfiguredWeight(3);
        support.setHealthy(false);
        support.retainConnection();
        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(support, 0);
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<UpstreamSupport>(),
                new RandomList<DnsServer.ResolveInterceptor>());
        RssClientHttpHandler.Query query = new RssClientHttpHandler.Query();
        query.fromMillis = 0L;
        query.toMillis = System.currentTimeMillis();
        query.fromValue = "1970-01-01T00:00";
        query.toValue = "2099-01-01T00:00";

        try {
            String html = RssClientHttpHandler.renderShadowUsersPage(Collections.<String, ShadowUser>emptyMap(), null, query,
                    RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS, snapshot);

            assertTrue(html.contains("Socks Servers"));
            assertTrue(html.contains("u@127.0.0.1:1080"));
            assertTrue(html.contains("DOWN"));
            assertTrue(html.contains(">3<"));
            assertTrue(html.contains(">0<"));
            assertTrue(html.contains(">1<"));
            assertTrue(!html.contains("secret"));
        } finally {
            support.releaseConnection();
        }
    }

    @Test
    public void renderShadowUsersPage_RendersClosingOldUpstreamReleaseWait() {
        AuthenticEndpoint currentEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "cur", "secret");
        currentEndpoint.getParameters().put("w", "1");
        UpstreamSupport currentSupport = new UpstreamSupport(currentEndpoint, null);
        currentSupport.setConfiguredWeight(1);
        RandomList<UpstreamSupport> currentServers = new RandomList<>();
        currentServers.add(currentSupport, 1);
        RssRuntime.UpstreamSnapshot currentSnapshot = new RssRuntime.UpstreamSnapshot(currentServers,
                new RandomList<UpstreamSupport>(), new RandomList<DnsServer.ResolveInterceptor>(),
                Collections.singletonList(currentEndpoint), Collections.<AuthenticEndpoint>emptyList());

        AuthenticEndpoint oldEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1081), "old", "secret");
        oldEndpoint.getParameters().put("w", "2");
        UpstreamSupport oldSupport = new UpstreamSupport(oldEndpoint, null);
        oldSupport.setConfiguredWeight(2);
        oldSupport.retainConnection();
        RandomList<UpstreamSupport> oldServers = new RandomList<>();
        oldServers.add(oldSupport, 2);
        RssRuntime.UpstreamSnapshot oldSnapshot = new RssRuntime.UpstreamSnapshot(oldServers,
                new RandomList<UpstreamSupport>(), new RandomList<DnsServer.ResolveInterceptor>(),
                Collections.singletonList(oldEndpoint), Collections.<AuthenticEndpoint>emptyList());
        oldSnapshot.closing = true;
        oldSnapshot.closeDeadlineMillis = System.currentTimeMillis() + RssClient.UPSTREAM_CLOSE_MAX_WAIT_MILLIS;

        RssClientHttpHandler.Query query = new RssClientHttpHandler.Query();
        query.fromMillis = 0L;
        query.toMillis = System.currentTimeMillis();
        query.fromValue = "1970-01-01T00:00";
        query.toValue = "2099-01-01T00:00";

        try {
            String html = RssClientHttpHandler.renderShadowUsersPage(Collections.<String, ShadowUser>emptyMap(), null, query,
                    RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS, Arrays.asList(currentSnapshot, oldSnapshot));

            assertTrue(html.contains("当前配置"));
            assertTrue(html.contains("旧配置"));
            assertTrue(html.contains("释放等待"));
            assertTrue(html.contains("cur@127.0.0.1:1080"));
            assertTrue(html.contains("old@127.0.0.1:1081"));
            assertTrue(!html.contains("secret"));
        } finally {
            oldSupport.releaseConnection();
        }
    }

    @Test
    public void renderShadowUsersPage_RendersConfiguredDisabledSocksServer() {
        AuthenticEndpoint enabledEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "on", "secret");
        enabledEndpoint.getParameters().put("w", "3");
        UpstreamSupport support = new UpstreamSupport(enabledEndpoint, null);
        support.setConfiguredWeight(3);
        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(support, 3);

        AuthenticEndpoint disabledEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1082), "off", "secret");
        disabledEndpoint.getParameters().put("w", "0");
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<UpstreamSupport>(),
                new RandomList<DnsServer.ResolveInterceptor>(), Arrays.asList(enabledEndpoint, disabledEndpoint),
                Collections.<AuthenticEndpoint>emptyList());
        RssClientHttpHandler.Query query = new RssClientHttpHandler.Query();
        query.fromMillis = 0L;
        query.toMillis = System.currentTimeMillis();
        query.fromValue = "1970-01-01T00:00";
        query.toValue = "2099-01-01T00:00";

        String html = RssClientHttpHandler.renderShadowUsersPage(Collections.<String, ShadowUser>emptyMap(), null, query,
                RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS, snapshot);

        assertTrue(html.contains("off@127.0.0.1:1082"));
        assertTrue(html.contains("DISABLED"));
        assertTrue(!html.contains("secret"));
    }

    @Test
    public void resolveRpcRequestTimeoutMillis_UsesConfiguredOrConnectBound() {
        RSSConf conf = new RSSConf();

        assertEquals(3000, RssClient.resolveRpcRequestTimeoutMillis(conf));
        conf.rpcRequestTimeoutMillis = 1200;
        assertEquals(1200, RssClient.resolveRpcRequestTimeoutMillis(conf));
        conf.rpcRequestTimeoutMillis = 0;
        conf.connectTimeoutSeconds = 1;
        assertEquals(1000, RssClient.resolveRpcRequestTimeoutMillis(conf));
        conf.connectTimeoutSeconds = 10;
        assertEquals(3000, RssClient.resolveRpcRequestTimeoutMillis(conf));
    }

    @Test
    public void normalizeAndValidateRssConfig_ClampsRuntimeFields() {
        RSSConf conf = validRssConf();
        conf.trafficRetentionDays = 0;
        conf.memoryRetentionHours = 0;
        conf.rpcMinSize = 4;
        conf.rpcMaxSize = 1;
        conf.connectTimeoutSeconds = -1;
        conf.ddnsJobSeconds = 60;
        conf.ddnsApiKey = "k";
        conf.ddnsDomains = Collections.singletonList("a.example.com");

        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));

        assertEquals(1, conf.trafficRetentionDays);
        assertEquals(RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS, conf.memoryRetentionHours);
        assertEquals(4, conf.rpcMinSize);
        assertEquals(4, conf.rpcMaxSize);
        assertEquals(1, conf.connectTimeoutSeconds);
        assertEquals(Collections.emptyList(), conf.udp2rawSocksServers);
    }

    @Test
    public void normalizeAndValidateRssConfig_RejectsDuplicateShadowUsers() {
        RSSConf conf = validRssConf();
        ShadowUser duplicate = new ShadowUser();
        duplicate.setUsername(conf.shadowUsers.get(0).getUsername());
        duplicate.setSsPort(2082);
        duplicate.setSsPwd("pwd2");
        duplicate.setSocksUser("inner2");
        conf.shadowUsers = java.util.Arrays.asList(conf.shadowUsers.get(0), duplicate);

        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void normalizeAndValidateRssConfig_AcceptsShadowUserScopedSocksServers() {
        RSSConf conf = validRssConf();
        RSSConf.SocksServer first = new RSSConf.SocksServer("primary",
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080?w=1"));
        RSSConf.SocksServer second = new RSSConf.SocksServer("backup",
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081?w=2"));
        conf.socksServers = Arrays.asList(first, second);
        conf.shadowUsers.get(0).setSocksServers(Collections.singletonList(" backup "));

        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        assertEquals(Collections.singletonList("backup"), conf.shadowUsers.get(0).getSocksServers());
    }

    @Test
    public void normalizeAndValidateRssConfig_RejectsUnknownShadowUserSocksServer() {
        RSSConf conf = validRssConf();
        conf.socksServers.get(0).setId("primary");
        conf.shadowUsers.get(0).setSocksServers(Collections.singletonList("missing"));

        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void resolveUserSocksServers_UsesShadowUserScopedList() {
        UpstreamSupport first = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080?w=1"), null);
        first.setConfiguredWeight(1);
        UpstreamSupport second = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081?w=1"), null);
        second.setConfiguredWeight(1);
        RandomList<UpstreamSupport> defaults = new RandomList<>();
        defaults.add(first, 1);
        RandomList<UpstreamSupport> userOnly = new RandomList<>();
        userOnly.add(second, 1);
        Map<String, RandomList<UpstreamSupport>> userServers = new LinkedHashMap<>();
        userServers.put("ss-rocky", userOnly);
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");

        assertSame(userOnly, RssClient.resolveUserSocksServers(defaults, userServers, user));
    }

    @Test
    public void updateUpstreamHealth_UpdatesShadowUserScopedWeights() {
        UpstreamSupport support = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080?w=1"), null);
        support.setConfiguredWeight(1);
        RandomList<UpstreamSupport> defaults = new RandomList<>();
        defaults.add(support, 1);
        RandomList<UpstreamSupport> userOnly = new RandomList<>();
        userOnly.add(support, 1);
        Map<String, RandomList<UpstreamSupport>> userServers = new LinkedHashMap<>();
        userServers.put("ss-rocky", userOnly);
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(defaults,
                new RandomList<UpstreamSupport>(), new RandomList<DnsServer.ResolveInterceptor>(),
                userServers, Collections.<RSSConf.SocksServer>emptyList(), Collections.<AuthenticEndpoint>emptyList());

        RssClient.updateUpstreamHealth(snapshot, defaults, support, false, true);

        assertEquals(0, defaults.getWeight(support));
        assertEquals(0, userOnly.getWeight(support));
    }

    @Test
    public void socksServerJsonReader_SupportsStringAndObjectFormats() {
        RSSConf conf = JSON.parseObject("{\"socksServers\":[\"u:p@127.0.0.1:1080?w=1\","
                + "{\"id\":\"backup\",\"endpoint\":\"u:p@127.0.0.1:1081?w=2\"}]}", RSSConf.class);

        assertEquals(2, conf.socksServers.size());
        assertTrue(conf.socksServers.get(0).getId() == null);
        assertEquals(1080, conf.socksServers.get(0).getEndpoint().requireEndpoint().getPort());
        assertEquals("backup", conf.socksServers.get(1).getId());
        assertEquals(1081, conf.socksServers.get(1).getEndpoint().requireEndpoint().getPort());
    }

    @Test
    public void configureOutboundConfig_RefreshesTimeoutAndUdpLeaseSettings() {
        RSSConf conf = new RSSConf();
        conf.connectTimeoutSeconds = 2;
        conf.tcpTimeoutSeconds = 3;
        conf.udpTimeoutSeconds = 4;
        conf.udpLeasePoolEnabled = true;
        conf.udpLeasePoolMinSize = 1;
        conf.udpLeasePoolMaxSize = 5;
        SocksConfig config = new SocksConfig();

        RssClient.configureOutboundConfig(conf, config);

        assertEquals(2000, config.getConnectTimeoutMillis());
        assertEquals(3, config.getReadTimeoutSeconds());
        assertEquals(4, config.getUdpReadTimeoutSeconds());
        assertTrue(config.isUdpLeasePoolEnabled());
        assertEquals(5, config.getUdpLeasePoolMaxSize());
    }

    @Test
    public void switchingRandomList_UsesLatestDelegateWithoutReplacingOwner() {
        RssRuntime.SwitchingRandomList<String> switching = new RssRuntime.SwitchingRandomList<>();
        RandomList<String> first = new RandomList<>();
        first.add("first", 1);
        switching.setDelegate(first);

        assertEquals("first", switching.next());

        RandomList<String> second = new RandomList<>();
        second.add("second", 1);
        switching.setDelegate(second);

        assertEquals("second", switching.next());
        assertEquals(Collections.singletonList("second"), switching.aliveList());
    }

    @Test
    public void inServerRestartRequired_DetectsOwnedConfigChanges() {
        RSSConf oldConf = validRssConf();
        RSSConf newConf = validRssConf();

        assertTrue(!RssClient.inServerRestartRequired(oldConf, newConf));

        newConf = validRssConf();
        newConf.connectTimeoutSeconds = oldConf.connectTimeoutSeconds + 1;
        assertTrue(RssClient.inServerRestartRequired(oldConf, newConf));

        newConf = validRssConf();
        newConf.kcptunClient = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 4093), "k", "p");
        assertTrue(RssClient.inServerRestartRequired(oldConf, newConf));
    }

    @Test
    public void shadowServerRestartRequired_DetectsConfigObjectChanges() {
        RSSConf oldConf = validRssConf();
        RSSConf newConf = validRssConf();

        assertTrue(!RssClient.shadowServerRestartRequired(oldConf, newConf));

        newConf.logFlags = oldConf.logFlags + 1;
        assertTrue(RssClient.shadowServerRestartRequired(oldConf, newConf));
    }

    private RSSConf validRssConf() {
        RSSConf conf = new RSSConf();
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(2081);
        user.setSsPwd("pwd");
        user.setSocksUser("inner");
        conf.shadowUsers = Collections.singletonList(user);
        conf.socksPwd = "socks-pwd";
        AuthenticEndpoint endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "p");
        endpoint.getParameters().put("w", "1");
        conf.socksServers = Collections.singletonList(new RSSConf.SocksServer(endpoint));
        return conf;
    }

    @SneakyThrows
    @Disabled("manual rss integration")
    @Test
    public void tstUdp() {
        InetSocketAddress socksUdpEp = Sockets.parseEndpoint("127.0.0.1:1080");
        InetSocketAddress ntpServer = Sockets.parseEndpoint("pool.ntp.org:123");

        CountDownLatch latch = new CountDownLatch(10);
        Channel channel = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                ByteBuf buf = msg.content();
                System.out.println("readableBytes: " + buf.readableBytes() + ", hex: " + ByteBufUtil.prettyHexDump(buf));
                if (buf.readableBytes() < 48) {
                    return;
                }
                long[] result = new long[2];
                result[0] = System.currentTimeMillis();
                UnresolvedEndpoint dstEp = org.rx.net.socks.UdpManager.socks5Decode(buf);
                System.out.println("from dstEp: " + dstEp);

                buf.skipBytes(40);
                long transmitTimestamp = buf.readLong();

                long ntpSeconds = transmitTimestamp >>> 32;
                long ntpFraction = transmitTimestamp & 0xFFFFFFFFL;
                long milliseconds = (ntpSeconds * 1000) + ((ntpFraction * 1000) / 0x100000000L);
                long epochOffset = 2208988800000L;
                long serverTimeMillis = milliseconds - epochOffset;
                result[1] = serverTimeMillis;

                DateTime localTime = new DateTime(result[0]);
                DateTime serverTime = new DateTime(result[1]);
                System.out.println("NTP服务器" + ntpServer + "时间: " + serverTime);
                System.out.println("本地系统时间: " + localTime);
                latch.countDown();
            }
        })).bind(0).sync().channel();

        for (int i = 0; i < latch.getCount(); i++) {
            Tasks.run(() -> {
                ByteBuf buf = channel.alloc().directBuffer(48);
                buf.writeByte(0x1B);
                buf.writeZero(47);
                channel.writeAndFlush(new DatagramPacket(org.rx.net.socks.UdpManager.socks5Encode(buf, ntpServer), socksUdpEp));
            });
        }

        assert latch.await(20000, TimeUnit.MILLISECONDS);
    }

    @SneakyThrows
    @Disabled("manual rss integration")
    @Test
    public void ssProxy() {
        createSocksSvr(false);
        System.in.read();
    }

    void createSocksSvr(boolean udp2raw) {
        int shadowDnsPort = 853;
        int outSvrPort = 2080;
        int inSvrPort = 2090;
        int ssPort = 2092;

        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(shadowDnsPort);
        new DnsServer(shadowDnsPort);

        InetSocketAddress outSrvEp = Sockets.newLoopbackEndpoint(outSvrPort);
        SocksConfig outConf = new SocksConfig(outSrvEp.getPort());
        outConf.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
        outConf.setConnectTimeoutMillis(connectTimeoutMillis);
        SocksUser usr = new SocksUser(socks5Usr);
        usr.setPassword(socks5Pwd);
        outConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(outConf, new SocksAuthenticator(Collections.singletonList(usr)));
        backSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);

        RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(outSrvEp.getPort() + 1));
        rpcServerConf.getTcpConfig().setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
        Remoting.register(new RssRpcApp(backSvr), rpcServerConf);

        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RpcClientConfig<SocksRpcContract> rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(outSrvEp, outSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.getTcpConfig().setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
        socksServers.add(new UpstreamSupport(new AuthenticEndpoint(outSrvEp, socks5Usr, socks5Pwd), Remoting.createFacade(SocksRpcContract.class, rpcClientConf)));

        SocksConfig inConf = new SocksConfig(inSvrPort);
        inConf.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
        inConf.setConnectTimeoutMillis(connectTimeoutMillis);
        inConf.setEnableUdp2raw(udp2raw);
        inConf.setUdp2rawClient(outSrvEp);
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                return;
            }
            if (Sockets.isBypass(bypassHosts, dstEp.getHost())) {
                e.setUpstream(new Upstream(dstEp));
            }
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            e.setUpstream(new SocksTcpUpstream(dstEp, inConf, socksServers.next()));
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            e.setUpstream(new SocksUdpUpstream(dstEp, inConf, socksServers.next()));
        });

        AuthenticEndpoint inSrvEp = new AuthenticEndpoint(Sockets.newLoopbackEndpoint(inSvrPort));
        ShadowsocksConfig frontConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(ssPort),
                CipherKind.AES_128_GCM.getCipherName(), socks5Pwd);
        ShadowsocksServer frontSvr = new ShadowsocksServer(frontConf);
        frontSvr.onTcpRoute.replace((s, e) -> e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
        frontSvr.onUdpRoute.replace((s, e) -> e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
    }
}
