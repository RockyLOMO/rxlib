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
import org.rx.net.dns.DnsResolveInterceptor;
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
import org.rx.net.socks.SocksRpcCapabilities;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.UdpClientUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.function.TripleAction;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Sys.toJsonString;

public class RssTest extends AbstractTester {
    @Test
    public void rrpServer_IsDisabledByDefaultAndRequiresExplicitEnable() {
        String oldValue = System.getProperty(RssClient.RRP_SERVER_ENABLED_PROPERTY);
        try {
            System.clearProperty(RssClient.RRP_SERVER_ENABLED_PROPERTY);
            assertFalse(RssClient.isRrpServerEnabled());

            RssClientConf conf = new RssClientConf();
            conf.rrpToken = "token";
            conf.rrpPort = 18888;
            assertFalse(RssClient.shouldEnableRrpServer(conf));

            System.setProperty(RssClient.RRP_SERVER_ENABLED_PROPERTY, "true");
            assertTrue(RssClient.isRrpServerEnabled());
            assertTrue(RssClient.shouldEnableRrpServer(conf));
        } finally {
            if (oldValue == null) {
                System.clearProperty(RssClient.RRP_SERVER_ENABLED_PROPERTY);
            } else {
                System.setProperty(RssClient.RRP_SERVER_ENABLED_PROPERTY, oldValue);
            }
        }
    }

    final int connectTimeoutMillis = 30000;
    final String socks5Usr = "rocky";
    final String socks5Pwd = "123456";
    final CopyOnWriteArraySet<String> bypassHosts = new CopyOnWriteArraySet<String>(RxConfig.INSTANCE.getNet().getBypassHosts()) {
        {
            add("*qq*");
        }
    };

    @Test
    public void acceptDrainSignal_RequiresFreshUsr1Token() throws Exception {
        String oldDir = System.getProperty(RssClient.PROCESS_DRAIN_TOKEN_DIR_PROPERTY);
        String oldTtl = System.getProperty(RssClient.PROCESS_DRAIN_TOKEN_TTL_PROPERTY);
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "rss-drain-token-test-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            System.setProperty(RssClient.PROCESS_DRAIN_TOKEN_DIR_PROPERTY, dir.getAbsolutePath());
            System.clearProperty(RssClient.PROCESS_DRAIN_TOKEN_TTL_PROPERTY);

            File token = RssClient.RssProcessControl.drainTokenFile();
            assertTrue(!RssClient.RssProcessControl.acceptDrainSignal("SIGUSR1"));
            assertTrue(!RssClient.RssProcessControl.acceptDrainSignal("SIGUSR2"));
            assertTrue(RssClient.RssProcessControl.acceptDrainSignal("SIGTERM"));

            assertTrue(token.createNewFile());
            assertTrue(RssClient.RssProcessControl.acceptDrainSignal("SIGUSR1"));
            assertTrue(!token.exists());

            assertTrue(token.createNewFile());
            assertTrue(token.setLastModified(System.currentTimeMillis() - 10_000L));
            System.setProperty(RssClient.PROCESS_DRAIN_TOKEN_TTL_PROPERTY, "1");
            assertTrue(!RssClient.RssProcessControl.acceptDrainSignal("SIGUSR1"));
            assertTrue(!token.exists());
        } finally {
            if (oldDir == null) {
                System.clearProperty(RssClient.PROCESS_DRAIN_TOKEN_DIR_PROPERTY);
            } else {
                System.setProperty(RssClient.PROCESS_DRAIN_TOKEN_DIR_PROPERTY, oldDir);
            }
            if (oldTtl == null) {
                System.clearProperty(RssClient.PROCESS_DRAIN_TOKEN_TTL_PROPERTY);
            } else {
                System.setProperty(RssClient.PROCESS_DRAIN_TOKEN_TTL_PROPERTY, oldTtl);
            }
            File token = new File(dir, "rss-drain-" + RssClient.RssProcessControl.currentProcessId() + ".token");
            if (token.exists()) {
                assertTrue(token.delete());
            }
            assertTrue(dir.delete());
        }
    }

    @Test
    public void resolveClientInListenAddress_DefaultToLocalAddress() {
        RssClientConf conf = new RssClientConf();

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertEquals(new LocalAddress("rss-in-6885"), address);
    }

    @Test
    public void resolveClientInListenAddress_BindPortUsesLoopback() {
        RssClientConf conf = new RssClientConf();
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
        RssClientConf conf = new RssClientConf();
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
        RssClientConf rssConf = yamlConfiguration.readAs(null, RssClientConf.class);
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
        ServerResponse response = new ServerResponse();
        try {
            RxConfig.INSTANCE.setRtoken("test-rtoken");
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.getStatus());
            assertEquals("Basic realm=\"rxlib-diagnostic\", charset=\"UTF-8\"",
                    response.getHeaders().get(HttpHeaderNames.WWW_AUTHENTICATE));
        } finally {
            if (response.getContent() != null && response.getContent().refCnt() > 0) {
                response.getContent().release();
            }
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void rssClientHttpHandler_ReturnsServiceUnavailableWhenRtokenEmpty() {
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        ServerResponse response = new ServerResponse();
        try {
            RxConfig.INSTANCE.setRtoken(null);
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.getStatus());
            assertTrue(response.getContent().toString(StandardCharsets.UTF_8).contains("app.rtoken"));
        } finally {
            if (response.getContent() != null && response.getContent().refCnt() > 0) {
                response.getContent().release();
            }
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void rssClientHttpHandler_AllowsAuthorizedRequest() {
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        ServerResponse response = new ServerResponse();
        try {
            RxConfig.INSTANCE.setRtoken("test-rtoken");
            RssClientHttpHandler handler = new RssClientHttpHandler(Collections.<String, ShadowUser>emptyMap());
            ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 10086),
                    RssClientHttpHandler.SHADOW_USERS_PAGE_PATH, HttpMethod.GET);
            String auth = Base64.getEncoder().encodeToString("rxlib:test-rtoken".getBytes(StandardCharsets.UTF_8));
            request.getHeaders().set(HttpHeaderNames.AUTHORIZATION, "Basic " + auth);

            handler.handle(request, response);

            assertEquals(HttpResponseStatus.OK, response.getStatus());
            assertTrue(response.getContent().toString(StandardCharsets.UTF_8).contains("RSS SS 用户信息"));
        } finally {
            if (response.getContent() != null && response.getContent().refCnt() > 0) {
                response.getContent().release();
            }
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
            assertTrue(html.contains("上下行流量走势图"));
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
    public void resolveShadowEndpoint_AttachesUserTagToLocalEndpoint() {
        SocketAddress inSvrAddress = new LocalAddress("rss-in-6885");
        AuthenticEndpoint endpoint = RssClient.resolveShadowEndpoint(inSvrAddress, "ss-user");

        assertSame(inSvrAddress, endpoint.getEndpoint());
        assertEquals("ss-user", endpoint.getParameters().get(org.rx.net.socks.SocksConnectionTagRegistry.PARAM_NAME));
    }

    @Test
    public void shouldScheduleDdns_RequiresPositivePeriodAndDomains() {
        RssClientConf conf = new RssClientConf();

        assertTrue(!RssClient.shouldScheduleDdns(conf));
        conf.ddnsDomains = Collections.singletonList("a.example.com");
        assertTrue(!RssClient.shouldScheduleDdns(conf));
        conf.ddnsJobSeconds = 60;
        assertTrue(RssClient.shouldScheduleDdns(conf));
    }

    @Test
    public void applyUdpLeasePool_EnablesAndClampsRssConfig() {
        RssClientConf conf = new RssClientConf();
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
        RssClientConf conf = new RssClientConf();
        conf.udpLeasePoolEnabled = false;
        SocksConfig config = new SocksConfig();
        config.setUdpLeasePoolEnabled(true);

        RssClient.applyUdpLeasePool(conf, config);

        assertTrue(!config.isUdpLeasePoolEnabled());
    }

    @Test
    public void socksTcpUpstream_FakeTokenIsScopedBySupportEndpoint() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch registered = new CountDownLatch(2);
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                calls.incrementAndGet();
                registered.countDown();
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        SocksConfig config = new SocksConfig();
        String dstHost = "fake-key-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport routedA = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);
        UpstreamSupport routedB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.101", 4093)), facade);

        InetSocketAddress fakeA = new SocksTcpUpstream(dstEp, config, routedA).prepareDestination();
        InetSocketAddress fakeB = new SocksTcpUpstream(dstEp, config, routedB).prepareDestination();

        assertNotEquals(fakeA.getHostString(), fakeB.getHostString());
        assertTrue(fakeA.getHostString().endsWith(SocksRpcContract.FAKE_HOST_SUFFIX));
        assertTrue(fakeB.getHostString().endsWith(SocksRpcContract.FAKE_HOST_SUFFIX));
        assertTrue(registered.await(3, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
    }

    @Test
    public void socksTcpUpstream_FakeEndpointUsesParseableHostPortForUnresolvedDomain() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        final String[] capturedEndpoint = new String[1];
        final CountDownLatch registered = new CountDownLatch(1);
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                capturedEndpoint[0] = realEndpoint;
                registered.countDown();
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };

        SocksConfig config = new SocksConfig();
        String dstHost = "android-clients-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport routed = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        new SocksTcpUpstream(dstEp, config, routed).prepareDestination();

        assertTrue(registered.await(3, TimeUnit.SECONDS));
        assertEquals(dstHost + ":443", capturedEndpoint[0]);
        assertEquals(dstHost, org.rx.net.Sockets.parseEndpoint(capturedEndpoint[0]).getHostString());
    }

    @Test
    public void socksTcpUpstream_FakeEndpointRegistrationDoesNotBlockCaller() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        Tasks.runAsync(() -> Boolean.TRUE).get(3, TimeUnit.SECONDS);
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        CountDownLatch registrationFinished = new CountDownLatch(1);
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                registrationStarted.countDown();
                try {
                    return releaseRegistration.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    registrationFinished.countDown();
                }
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        String dstHost = "non-blocking-fake-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport routed = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        long start = System.nanoTime();
        InetSocketAddress fakeEp;
        java.util.concurrent.CompletableFuture<Boolean> registration = null;
        try {
            SocksTcpUpstream upstream = new SocksTcpUpstream(dstEp, new SocksConfig(), routed);
            registration = upstream.prepareDestinationRegistration();
            fakeEp = upstream.getDestination();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis < 500L, "fake endpoint RPC must not block the caller/EventLoop");
            assertFalse(registration.isDone(), "registration should remain pending while the RPC facade is blocked");
            assertTrue(registrationStarted.await(3, TimeUnit.SECONDS));
            assertTrue(SocksRpcContract.isFakeHost(fakeEp.getHostString()));
            assertEquals(SocksRpcContract.FAKE_TOKEN_LENGTH + SocksRpcContract.FAKE_HOST_SUFFIX.length(),
                    fakeEp.getHostString().length());
            assertEquals(dstHost + ":443", SocksTcpUpstream.cachedFakeEndpoint(fakeEp.getHostString()));
        } finally {
            releaseRegistration.countDown();
        }
        assertTrue(registrationFinished.await(3, TimeUnit.SECONDS));
        assertNotNull(registration);
        assertTrue(registration.get(3, TimeUnit.SECONDS));
    }

    @Test
    public void socksTcpUpstream_ServerAckCacheSkipsDuplicateAndRecoveryInvalidatesIt() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        AtomicInteger calls = new AtomicInteger();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                calls.incrementAndGet();
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        String dstHost = "fake-ack-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport support = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        SocksTcpUpstream first = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        assertTrue(first.prepareDestinationRegistration().get(3, TimeUnit.SECONDS));
        String fakeHost = first.getDestination().getHostString();
        assertTrue(SocksRpcContract.isFakeHost(fakeHost));

        SocksTcpUpstream cached = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        assertTrue(cached.prepareDestinationRegistration().get(100, TimeUnit.MILLISECONDS));
        assertEquals(1, calls.get(), "server ACK cache should avoid duplicate mapping RPC");

        SocksTcpUpstream.invalidateFakeEndpointRegistration(fakeHost);
        SocksTcpUpstream afterServerMiss = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        assertTrue(afterServerMiss.prepareDestinationRegistration().get(3, TimeUnit.SECONDS));
        assertEquals(2, calls.get(), "server recovery request should force the next mapping RPC");
    }

    @Test
    public void socksTcpUpstream_TokenCollisionRetriesWithoutUsingCollidingMapping() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        AtomicInteger calls = new AtomicInteger();
        List<String> fakeHosts = new java.util.concurrent.CopyOnWriteArrayList<>();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                fakeHosts.add(fakeHost);
                return calls.incrementAndGet() > 1;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        String dstHost = "fake-collision-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport support = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        SocksTcpUpstream upstream = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        assertTrue(upstream.prepareDestinationRegistration().get(3, TimeUnit.SECONDS));

        assertEquals(2, calls.get());
        assertEquals(2, fakeHosts.size());
        assertNotEquals(fakeHosts.get(0), fakeHosts.get(1));
        assertEquals(fakeHosts.get(1), upstream.getDestination().getHostString());
        assertNull(SocksTcpUpstream.cachedFakeEndpoint(fakeHosts.get(0)),
                "colliding token must not remain in the client recovery cache");
        assertEquals(dstHost + ":443", SocksTcpUpstream.cachedFakeEndpoint(fakeHosts.get(1)));
    }

    @Test
    public void socksTcpUpstream_ConcurrentRegistrationIsCoalescedByToken() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                calls.incrementAndGet();
                registrationStarted.countDown();
                try {
                    return releaseRegistration.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };
        String dstHost = "fake-coalesce-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport support = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        SocksTcpUpstream first = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        SocksTcpUpstream second = new SocksTcpUpstream(dstEp, new SocksConfig(), support);
        java.util.concurrent.CompletableFuture<Boolean> firstRegistration = first.prepareDestinationRegistration();
        java.util.concurrent.CompletableFuture<Boolean> secondRegistration = second.prepareDestinationRegistration();
        try {
            assertTrue(registrationStarted.await(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            releaseRegistration.countDown();
        }
        assertTrue(firstRegistration.get(3, TimeUnit.SECONDS));
        assertTrue(secondRegistration.get(3, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    public void socksTcpUpstream_CachesFakeEndpointWhenRpcPushFails() {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                throw new IllegalStateException("rpc unavailable");
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                return Collections.emptyList();
            }
        };

        SocksConfig config = new SocksConfig();
        String dstHost = "recover-cache-" + System.nanoTime() + ".example";
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
        UpstreamSupport routed = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.100", 4093)), facade);

        InetSocketAddress fakeEp = new SocksTcpUpstream(dstEp, config, routed).prepareDestination();
        assertTrue(SocksRpcContract.isFakeHost(fakeEp.getHostString()));
        assertEquals(dstHost + ":443", SocksTcpUpstream.cachedFakeEndpoint(fakeEp.getHostString()));
    }

    @Test
    public void rssRpcApp_FakeEndpointRejectsTokenCollisionWithoutOverwriting() {
        String fakeHost = SocksRpcContract.newFakeHost();
        try {
            SocksRpcContract.fakeDict().put(fakeHost,
                    org.rx.net.Sockets.newUnresolvedEndpoint("existing.example", 443),
                    org.rx.core.CachePolicy.absolute(60));

            RssRpcApp app = new RssRpcApp(org.mockito.Mockito.mock(SocksProxyServer.class));
            assertFalse(app.fakeEndpoint(fakeHost, "android.clients.google.com:443", SocksRpcContract.rpcToken()));

            assertEquals("existing.example", SocksRpcContract.fakeDict().get(fakeHost).getHostString());
        } finally {
            SocksRpcContract.fakeDict().remove(fakeHost);
        }
    }

    @Test
    public void socksRpcContract_FakeRecoverWaitMillisReadsSystemProperty() {
        String key = SocksRpcContract.FAKE_RECOVER_WAIT_MILLIS_PROPERTY;
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertEquals(SocksRpcContract.FAKE_RECOVER_WAIT_MILLIS, SocksRpcContract.fakeRecoverWaitMillis());

            System.setProperty(key, "1500");
            assertEquals(1500L, SocksRpcContract.fakeRecoverWaitMillis());

            System.setProperty(key, "0");
            assertEquals(SocksRpcContract.FAKE_RECOVER_WAIT_MILLIS, SocksRpcContract.fakeRecoverWaitMillis());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    public void socksRpcContract_FakeRegisterWaitMillisReadsSystemProperty() {
        String key = SocksRpcContract.FAKE_REGISTER_WAIT_MILLIS_PROPERTY;
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertEquals(SocksRpcContract.FAKE_REGISTER_WAIT_MILLIS, SocksRpcContract.fakeRegisterWaitMillis());

            System.setProperty(key, "1500");
            assertEquals(1500L, SocksRpcContract.fakeRegisterWaitMillis());

            System.setProperty(key, "0");
            assertEquals(SocksRpcContract.FAKE_REGISTER_WAIT_MILLIS, SocksRpcContract.fakeRegisterWaitMillis());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    public void rssRpcApp_RecoverFakeEndpointRequestsClientCachedMappingViaRpcEvent() throws Exception {
        org.rx.core.Cache.getInstance(org.rx.core.cache.MemoryCache.class);
        int rpcPort = freePort();
        SocksProxyServer backSvr = new SocksProxyServer(new SocksConfig(new LocalAddress("RSS_FAKE_RECOVER_RPC_BACK")));
        TcpServer rpcServer = null;
        SocksRpcContract facade = null;
        String fakeHost = null;
        try {
            RssRpcApp app = new RssRpcApp(backSvr);
            RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(rpcPort));
            rpcServerConf.getHybridConfig().setEnableUdpDirect(false);
            rpcServerConf.getHybridConfig().setEnableUdpHolePunch(false);
            rpcServerConf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
            rpcServerConf.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
            rpcServerConf.getTcpConfig().setConnectTimeoutMillis(SocksRpcContract.FAKE_RECOVER_RPC_TIMEOUT_MILLIS);
            rpcServer = Remoting.register(app, rpcServerConf);

            InetSocketAddress rpcEndpoint = new InetSocketAddress("127.0.0.1", rpcPort);
            RpcClientConfig<SocksRpcContract> rpcClientConf = RpcClientConfig.poolMode(rpcEndpoint, 1, 1);
            rpcClientConf.getHybridConfig().setEnableUdpDirect(false);
            rpcClientConf.getHybridConfig().setEnableUdpHolePunch(false);
            SocksRpcContract delegate = Remoting.createFacade(SocksRpcContract.class, rpcClientConf);
            RpcClientConfig<SocksRpcContract> eventClientConf =
                    RpcClientConfig.statefulMode(rpcEndpoint, SocksRpcContract.RPC_EVENT_VERSION);
            eventClientConf.getHybridConfig().setEnableUdpDirect(false);
            eventClientConf.getHybridConfig().setEnableUdpHolePunch(false);
            SocksRpcContract eventDelegate = Remoting.createFacade(SocksRpcContract.class, eventClientConf);
            facade = new RssClient.ForwardingSocksRpcContract(delegate, eventDelegate);
            assertTrue(Remoting.ping(eventDelegate, 1000));
            Thread.sleep(200L);

            String dstHost = "recover-" + System.nanoTime() + ".example";
            InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(dstHost, 443);
            UpstreamSupport upstream = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 9900)), facade);
            SocksTcpUpstream tcpUpstream = new SocksTcpUpstream(dstEp, new SocksConfig(), upstream);
            java.util.concurrent.CompletableFuture<Boolean> registration = tcpUpstream.prepareDestinationRegistration();
            InetSocketAddress fakeEp = tcpUpstream.getDestination();
            fakeHost = fakeEp.getHostString();
            assertTrue(SocksRpcContract.isFakeHost(fakeHost));
            assertEquals(dstHost + ":443", SocksTcpUpstream.cachedFakeEndpoint(fakeHost));

            assertTrue(registration.get(5, TimeUnit.SECONDS));
            assertNotNull(SocksRpcContract.fakeDict().get(fakeHost));

            SocksRpcContract.fakeDict().remove(fakeHost);
            InetSocketAddress recovered = app.recoverFakeEndpoint(fakeHost);

            assertNotNull(recovered);
            assertEquals(dstHost, recovered.getHostString());
            assertEquals(443, recovered.getPort());
            assertEquals(dstHost, SocksRpcContract.fakeDict().get(fakeHost).getHostString());
        } finally {
            if (facade != null) {
                facade.close();
            }
            if (rpcServer != null) {
                rpcServer.close();
            }
            if (fakeHost != null) {
                SocksRpcContract.fakeDict().remove(fakeHost);
            }
            backSvr.close();
        }
    }

    @Test
    @SneakyThrows
    public void nextUpstream_ThrowsWhenNoServerAvailable() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();

            assertThrows(InvalidException.class,
                    () -> RssClient.nextUpstream(new RandomList<UpstreamSupport>(), java.net.InetAddress.getByName("127.0.0.1")));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    @SneakyThrows
    public void nextUpstream_SkipsUnhealthyCachedServer() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            RandomList<UpstreamSupport> servers = new RandomList<>();
            UpstreamSupport first = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), null);
            first.setConfiguredWeight(1);
            servers.add(first, 1);
            InetAddress source = InetAddress.getByName("127.0.0.1");

            assertSame(first, RssClient.nextUpstream(servers, source, null, true, 60));

            UpstreamSupport second = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1081)), null);
            second.setConfiguredWeight(1);
            servers.add(second, 1);
            first.setHealthy(false);
            servers.setWeight(first, 0);

            assertSame(second, RssClient.nextUpstream(servers, source, null, true, 60));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    @SneakyThrows
    public void nextUpstream_FailOpenUsesConfiguredWeightWhenRuntimeWeightsAreZero() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            RssClient.rssConf.upstreamFailOpenWhenAllDown = true;
            RandomList<UpstreamSupport> servers = new RandomList<>();
            UpstreamSupport main = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), null);
            main.setConfiguredWeight(1);
            main.setHealthy(false);
            servers.add(main, 0);

            assertSame(main, RssClient.nextUpstream(servers, InetAddress.getByName("61.169.146.210")));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    @SneakyThrows
    public void nextUpstream_FailCloseThrowsWhenRuntimeWeightsAreZero() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClient.rssConf = new RssClientConf();
            RssClient.rssConf.upstreamFailOpenWhenAllDown = false;
            RandomList<UpstreamSupport> servers = new RandomList<>();
            UpstreamSupport main = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080)), null);
            main.setConfiguredWeight(1);
            main.setHealthy(false);
            servers.add(main, 0);

            assertThrows(InvalidException.class,
                    () -> RssClient.nextUpstream(servers, InetAddress.getByName("61.169.146.210")));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void closeUpstreamsWhenIdle_WaitsForActiveConnections() {
        AtomicInteger closeCount = new AtomicInteger();
        SocksRpcContract facade = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
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
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<DnsResolveInterceptor>(), null, null);

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
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
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
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<DnsResolveInterceptor>(), null, null);

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
    public void forwardingDns_proxyRouteNullRemoteResultDoesNotFallbackToLocalDns() throws Exception {
        RssClientConf old = RssClient.rssConf;
        RssClientConf conf = validRssConf();
        AtomicInteger calls = new AtomicInteger();
        SocksRpcContract delegate = new SocksRpcContract() {
            @Override
            public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                return true;
            }

            @Override
            public void addWhiteList(InetAddress endpoint, String token) {
            }

            @Override
            public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                calls.incrementAndGet();
                return null;
            }
        };

        try {
            RssClient.rssConf = conf;
            RssClient.ForwardingSocksRpcContract contract =
                    new RssClient.ForwardingSocksRpcContract(delegate);

            List<InetAddress> result = contract.resolveHost(
                    InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), "www.google.com");

            assertEquals(1, calls.get());
            assertTrue(result.isEmpty());
        } finally {
            RssClient.rssConf = old;
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
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<DnsResolveInterceptor>(), null, null);
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
                new RandomList<DnsResolveInterceptor>(), null,
                Collections.singletonList(new RssClientConf.SocksServer(null, RssClient.weightOf(currentEndpoint), currentEndpoint)));

        AuthenticEndpoint oldEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1081), "old", "secret");
        oldEndpoint.getParameters().put("w", "2");
        UpstreamSupport oldSupport = new UpstreamSupport(oldEndpoint, null);
        oldSupport.setConfiguredWeight(2);
        oldSupport.retainConnection();
        RandomList<UpstreamSupport> oldServers = new RandomList<>();
        oldServers.add(oldSupport, 2);
        RssRuntime.UpstreamSnapshot oldSnapshot = new RssRuntime.UpstreamSnapshot(oldServers,
                new RandomList<DnsResolveInterceptor>(), null,
                Collections.singletonList(new RssClientConf.SocksServer(null, RssClient.weightOf(oldEndpoint), oldEndpoint)));
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
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers, new RandomList<DnsResolveInterceptor>(), null,
                Arrays.asList(new RssClientConf.SocksServer(null, 3, enabledEndpoint), new RssClientConf.SocksServer(null, 0, disabledEndpoint)));
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
        RssClientConf conf = new RssClientConf();

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
    public void controlPlaneIsScopedToTheConfiguredPeerAndNoFacadeIsCreatedForStandardSocks() {
        RssClientConf conf = validRssConf();
        RssClientConf.SocksServer main = conf.socksServers.get(0);
        RssClientConf.SocksServer other = new RssClientConf.SocksServer("second", 1, AuthenticEndpoint.valueOf("127.0.0.1:18080"));
        RssClientConf.SocksServer standard = new RssClientConf.SocksServer("standard", 2, other.endpoint);
        standard.type = RssClientConf.UpstreamType.SOCKS5;
        conf.socksServers = Arrays.asList(main, other, standard);
        RssRuntime.UpstreamSnapshot snapshot = RssClient.buildUpstreams(conf);
        try {
            assertTrue(snapshot.socksServers.get(0).getFacade() != snapshot.socksServers.get(1).getFacade());
            assertNull(snapshot.socksServers.get(2).getFacade());
            assertEquals(2, snapshot.dnsInterceptors.size());
        } finally {
            RssClient.closeUpstreamsQuietly(snapshot);
        }
    }

    @Test
    public void normalizeAndValidateRssConfig_ClampsRuntimeFields() {
        RssClientConf conf = validRssConf();
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
        assertEquals(1, conf.socksServers.size());
    }

    @Test
    public void normalizeAndValidateRssConfig_RejectsDuplicateShadowUsers() {
        RssClientConf conf = validRssConf();
        ShadowUser duplicate = new ShadowUser();
        duplicate.setUsername(conf.shadowUsers.get(0).getUsername());
        duplicate.setSsPort(2082);
        duplicate.setSsPwd("pwd2");
        duplicate.setSocksUser("inner2");
        conf.shadowUsers = java.util.Arrays.asList(conf.shadowUsers.get(0), duplicate);

        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void shadowUserCipherDefaultsToAesAndRejectsUnknown() {
        ShadowUser user = new ShadowUser();
        user.setSsPwd("pwd");
        assertEquals(CipherKind.AES_256_GCM.getCipherName(), user.cipherName());
        user.setSsCipher(" chacha20-ietf-poly1305 ");
        assertEquals(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), user.cipherName());

        RssClientConf conf = validRssConf();
        conf.shadowUsers.get(0).setSsCipher("chacha20-ietf-poly1305");
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        conf.shadowUsers.get(0).setSsCipher("rc4-md5");
        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void shadowUserCipherHotReload_IsolatesOtherUsersAndRollsBackOnBindFailure() throws Exception {
        int upstreamPort = freePort();
        int rpcPort = freePort();
        int ssPortA = freePort();
        int ssPortB = freePort();
        int dnsPort = freePort();
        int nsRegPort = freePort();
        int nsSyncPort = freePort();
        int inPort = freePort();

        SocksConfig upCfg = new SocksConfig(upstreamPort);
        SocksUser socksUsr = new SocksUser("u");
        socksUsr.setPassword("p");
        SocksProxyServer upstreamSvr = new SocksProxyServer(upCfg, new SocksAuthenticator(Collections.singletonList(socksUsr)));

        ServerSocket tcpEcho = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        java.net.DatagramSocket udpEcho = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.util.concurrent.atomic.AtomicBoolean echoRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread tcpEchoThread = new Thread(() -> {
            while (echoRunning.get()) {
                try {
                    java.net.Socket s = tcpEcho.accept();
                    Thread worker = new Thread(() -> {
                        try (java.net.Socket sock = s) {
                            byte[] b = new byte[256];
                            int n;
                            while ((n = sock.getInputStream().read(b)) != -1) {
                                sock.getOutputStream().write(b, 0, n);
                                sock.getOutputStream().flush();
                            }
                        } catch (Exception ignored) {
                        }
                    });
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception ignored) {
                    break;
                }
            }
        });
        tcpEchoThread.setDaemon(true);
        tcpEchoThread.start();
        Thread udpEchoThread = new Thread(() -> {
            byte[] b = new byte[2048];
            while (echoRunning.get()) {
                try {
                    java.net.DatagramPacket p = new java.net.DatagramPacket(b, b.length);
                    udpEcho.receive(p);
                    udpEcho.send(new java.net.DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
                } catch (Exception ignored) {
                    break;
                }
            }
        });
        udpEchoThread.setDaemon(true);
        udpEchoThread.start();

        RssClientConf oldGlobalConf = RssClient.rssConf;
        RssRuntime runtime = null;
        java.net.Socket persistentClientB = null;
        org.rx.net.socks.encryption.ICrypto persistentCryptoB = null;
        try {
            RssClientConf conf1 = buildTwoUserRuntimeConf(upstreamPort, rpcPort, dnsPort, nsRegPort, nsSyncPort,
                    ssPortA, "pwdA1", null, ssPortB, "pwdB1", null);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf1));
            runtime = new RssRuntime(inPort, conf1);

            RssRuntime.ShadowServerRef refA1 = runtime.shadowServers.get("user-a");
            RssRuntime.ShadowServerRef refB1 = runtime.shadowServers.get("user-b");
            assertNotNull(refA1);
            assertNotNull(refB1);
            assertEquals(CipherKind.AES_256_GCM.getCipherName(), refA1.ssCipher);
            assertEquals(CipherKind.AES_256_GCM.getCipherName(), refB1.ssCipher);

            // Open a persistent TCP connection on user-b BEFORE reloading user-a's cipher.
            persistentClientB = new java.net.Socket("127.0.0.1", ssPortB);
            persistentClientB.setSoTimeout(4000);
            persistentCryptoB = org.rx.net.socks.encryption.ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), "pwdB1");
            ByteBuf initB = io.netty.buffer.Unpooled.buffer();
            org.rx.net.socks.UdpManager.encode(initB, "127.0.0.1", tcpEcho.getLocalPort());
            initB.writeBytes("keep-b-1".getBytes(StandardCharsets.UTF_8));
            persistentClientB.getOutputStream().write(drainBuf(persistentCryptoB.encrypt(initB)));
            persistentClientB.getOutputStream().flush();
            assertEquals("keep-b-1", readDecryptedString(persistentClientB, persistentCryptoB, 8));

            // 1. Hot-reload ONLY user-a to chacha20-ietf-poly1305; user-b must NOT rebind and its open stream must survive.
            RssClientConf conf2 = buildTwoUserRuntimeConf(upstreamPort, rpcPort, dnsPort, nsRegPort, nsSyncPort,
                    ssPortA, "pwdA1", "chacha20-ietf-poly1305", ssPortB, "pwdB1", null);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf2));
            runtime.reload(conf1, conf2);

            RssRuntime.ShadowServerRef refA2 = runtime.shadowServers.get("user-a");
            RssRuntime.ShadowServerRef refB2 = runtime.shadowServers.get("user-b");
            assertSame(refB1, refB2, "untouched user listener must not rebind");
            org.junit.jupiter.api.Assertions.assertNotSame(refA1, refA2, "changed cipher listener must rebind");
            assertEquals(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), refA2.ssCipher);

            // Verify user-b's existing TCP stream still echoes cleanly!
            ByteBuf nextB = io.netty.buffer.Unpooled.copiedBuffer("keep-b-2", StandardCharsets.UTF_8);
            persistentClientB.getOutputStream().write(drainBuf(persistentCryptoB.encrypt(nextB)));
            persistentClientB.getOutputStream().flush();
            assertEquals("keep-b-2", readDecryptedString(persistentClientB, persistentCryptoB, 8));

            // Verify user-a TCP + UDP succeed with chacha20-ietf-poly1305, and old aes-256-gcm fails!
            assertSsTcpEcho(ssPortA, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwdA1", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssPortA, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwdA1", udpEcho.getLocalPort(), true);
            assertSsTcpEcho(ssPortA, CipherKind.AES_256_GCM.getCipherName(), "pwdA1", tcpEcho.getLocalPort(), false);

            // 2. Canonical case/whitespace equivalence ("  CHACHA20-IETF-POLY1305  ") must NOT rebind user-a!
            RssClientConf confCanonical = buildTwoUserRuntimeConf(upstreamPort, rpcPort, dnsPort, nsRegPort, nsSyncPort,
                    ssPortA, "pwdA1", "  CHACHA20-IETF-POLY1305  ", ssPortB, "pwdB1", null);
            assertTrue(RssClient.normalizeAndValidateRssConfig(confCanonical));
            runtime.reload(conf2, confCanonical);
            assertSame(refA2, runtime.shadowServers.get("user-a"), "canonical equivalent cipher must not rebind");

            // 3. Inject bind failure during same-port cipher switch (chacha20 -> aes-128-gcm): must roll back and restore chacha20!
            RssClientConf confFail = buildTwoUserRuntimeConf(upstreamPort, rpcPort, dnsPort, nsRegPort, nsSyncPort,
                    ssPortA, "pwdA1", "aes-128-gcm", ssPortB, "pwdB1", null);
            assertTrue(RssClient.normalizeAndValidateRssConfig(confFail));
            runtime.shadowServerBindHook = usr -> {
                if ("user-a".equals(usr.getUsername())) {
                    throw new java.net.BindException("simulated bind failure on port " + usr.getSsPort());
                }
            };
            try {
                RssRuntime rt = runtime;
                assertThrows(Throwable.class, () -> rt.reload(confCanonical, confFail));
            } finally {
                runtime.shadowServerBindHook = null;
            }
            RssRuntime.ShadowServerRef restoredA = runtime.shadowServers.get("user-a");
            assertNotNull(restoredA);
            assertEquals(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), restoredA.ssCipher);
            assertSsTcpEcho(ssPortA, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwdA1", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssPortA, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwdA1", udpEcho.getLocalPort(), true);

            // 4. Simultaneous password + cipher change (chacha20/pwdA1 -> aes-128-gcm/pwdA2) succeeds cleanly.
            RssClientConf confBoth = buildTwoUserRuntimeConf(upstreamPort, rpcPort, dnsPort, nsRegPort, nsSyncPort,
                    ssPortA, "pwdA2", "aes-128-gcm", ssPortB, "pwdB1", null);
            assertTrue(RssClient.normalizeAndValidateRssConfig(confBoth));
            runtime.reload(confCanonical, confBoth);
            assertEquals(CipherKind.AES_128_GCM.getCipherName(), runtime.shadowServers.get("user-a").ssCipher);
            assertSsTcpEcho(ssPortA, CipherKind.AES_128_GCM.getCipherName(), "pwdA2", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssPortA, CipherKind.AES_128_GCM.getCipherName(), "pwdA2", udpEcho.getLocalPort(), true);
            assertSsTcpEcho(ssPortA, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwdA1", tcpEcho.getLocalPort(), false);
        } finally {
            echoRunning.set(false);
            if (persistentClientB != null) persistentClientB.close();
            if (persistentCryptoB instanceof AutoCloseable) ((AutoCloseable) persistentCryptoB).close();
            if (runtime != null) runtime.close();
            RssClient.rssConf = oldGlobalConf;
            tcpEcho.close();
            udpEcho.close();
            upstreamSvr.close();
        }
    }

    private static RssClientConf buildTwoUserRuntimeConf(int upstreamPort, int rpcPort, int dnsPort, int nsRegPort, int nsSyncPort,
                                                         int portA, String pwdA, String cipherA,
                                                         int portB, String pwdB, String cipherB) {
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "inner-socks-pwd";
        conf.rpcPort = rpcPort;
        conf.shadowDnsPort = dnsPort;
        conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};
        conf.rpcAutoWhiteListSeconds = 0;
        conf.upstreamHealthCheckSeconds = 60;
        conf.udpLeasePoolEnabled = false;
        conf.nameserver = new NameserverConfig();
        conf.nameserver.setRegisterPort(nsRegPort);
        conf.nameserver.setSyncPort(nsSyncPort);
        AuthenticEndpoint ep = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", upstreamPort), "u", "p");
        conf.socksServers = Collections.singletonList(new RssClientConf.SocksServer("primary", 1, ep));

        ShadowUser a = new ShadowUser();
        a.setUsername("user-a");
        a.setSocksUser("socks-a");
        a.setSsPort(portA);
        a.setSsPwd(pwdA);
        a.setSsCipher(cipherA);

        ShadowUser b = new ShadowUser();
        b.setUsername("user-b");
        b.setSocksUser("socks-b");
        b.setSsPort(portB);
        b.setSsPwd(pwdB);
        b.setSsCipher(cipherB);

        conf.shadowUsers = Arrays.asList(a, b);
        return conf;
    }

    @Test
    public void dns53AcrossShadowIngressPreservesResolverAddressAndTcpLength() throws Exception {
        int[] ports = freeTcpUdpPorts(8);
        RssClientConf previous = RssClient.rssConf;
        RssClientConf conf = buildTwoUserRuntimeConf(ports[0], ports[1], ports[2], ports[3], ports[4],
                ports[5], "dns-a", "aes-128-gcm", ports[6], "dns-b", "aes-128-gcm");
        conf.defaultRoute = new UserRule();
        conf.defaultRoute.setEnabled(Boolean.TRUE);
        conf.defaultRoute.setRules(Collections.singletonList("default direct"));
        // An unavailable ordinary proxy must not see these transactions.
        conf.socksServers.get(0).type = RssClientConf.UpstreamType.SOCKS5;
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        RssRuntime runtime = new RssRuntime(ports[7], conf);
        try {
            runtime.dnsSvr.addHosts("ingress.example.invalid", "192.0.2.38");
            ByteBuf request = io.netty.buffer.Unpooled.buffer();
            org.rx.net.dns.DoHMessageCodec.encodeQuery(request, 0x2134, "ingress.example.invalid",
                    io.netty.handler.codec.dns.DnsRecordType.A);
            byte[] question = drainBuf(request);
            org.rx.net.socks.encryption.ICrypto udp = org.rx.net.socks.encryption.ICrypto.get("aes-128-gcm", "dns-a", true);
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.setSoTimeout(5000);
                for (String original : new String[]{"192.0.2.1", "192.0.2.2"}) {
                    ByteBuf payload = io.netty.buffer.Unpooled.buffer();
                    org.rx.net.socks.UdpManager.encode(payload, original, 53);
                    byte[] wire = drainBuf(udp.encrypt(payload.writeBytes(question)));
                    socket.send(new java.net.DatagramPacket(wire, wire.length, InetAddress.getLoopbackAddress(), ports[5]));
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[2048], 2048);
                    socket.receive(packet);
                    ByteBuf reply = udp.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength()));
                    try {
                        InetSocketAddress from = org.rx.net.socks.UdpManager.decode(reply);
                        assertEquals(original, from.getHostString());
                        assertEquals(53, from.getPort());
                        assertEquals("192.0.2.38", org.rx.net.dns.DoHMessageCodec.decodeAddresses(reply.slice(), 0x2134).get(0).getHostAddress());
                    } finally {
                        reply.release();
                    }
                }
            } finally {
                if (udp instanceof AutoCloseable) ((AutoCloseable) udp).close();
            }
            org.rx.net.socks.encryption.ICrypto tcp = org.rx.net.socks.encryption.ICrypto.get("aes-128-gcm", "dns-b");
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", ports[6])) {
                socket.setSoTimeout(5000);
                ByteBuf payload = io.netty.buffer.Unpooled.buffer();
                org.rx.net.socks.UdpManager.encode(payload, "192.0.2.3", 53);
                socket.getOutputStream().write(drainBuf(tcp.encrypt(payload.writeShort(question.length).writeBytes(question))));
                ByteBuf reply = io.netty.buffer.Unpooled.buffer();
                try {
                    byte[] input = new byte[1024];
                    while (reply.readableBytes() < 2 || reply.readableBytes() < 2 + reply.getUnsignedShort(0)) {
                        int n = socket.getInputStream().read(input);
                        assertTrue(n > 0);
                        reply.writeBytes(drainBuf(tcp.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(input, 0, n))));
                    }
                    assertEquals(reply.readableBytes() - 2, reply.readUnsignedShort());
                    assertEquals("192.0.2.38", org.rx.net.dns.DoHMessageCodec.decodeAddresses(reply.slice(), 0x2134).get(0).getHostAddress());
                } finally {
                    reply.release();
                }
            } finally {
                if (tcp instanceof AutoCloseable) ((AutoCloseable) tcp).close();
            }
        } finally {
            runtime.close();
            RssClient.rssConf = previous;
        }
    }

    private static byte[] drainBuf(ByteBuf buf) {
        try {
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static String readDecryptedString(java.net.Socket sock, org.rx.net.socks.encryption.ICrypto crypto, int expectedLen) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (out.size() < expectedLen) {
            int n = sock.getInputStream().read(buf);
            if (n == -1) break;
            ByteBuf dec = crypto.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(buf, 0, n));
            try {
                out.write(drainBuf(dec.retain()));
            } finally {
                dec.release();
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertSsTcpEcho(int ssPort, String method, String pwd, int targetPort, boolean expectSuccess) throws Exception {
        org.rx.net.socks.encryption.ICrypto crypto = org.rx.net.socks.encryption.ICrypto.get(method, pwd);
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", ssPort)) {
            s.setSoTimeout(expectSuccess ? 4000 : 800);
            ByteBuf req = io.netty.buffer.Unpooled.buffer();
            org.rx.net.socks.UdpManager.encode(req, "127.0.0.1", targetPort);
            req.writeBytes("ping".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(drainBuf(crypto.encrypt(req)));
            s.getOutputStream().flush();
            if (expectSuccess) {
                assertEquals("ping", readDecryptedString(s, crypto, 4));
            } else {
                int r;
                try {
                    r = s.getInputStream().read();
                } catch (java.io.IOException e) {
                    return;
                }
                assertEquals(-1, r);
            }
        } finally {
            if (crypto instanceof AutoCloseable) ((AutoCloseable) crypto).close();
        }
    }

    private static void assertSsUdpEcho(int ssPort, String method, String pwd, int targetPort, boolean expectSuccess) throws Exception {
        org.rx.net.socks.encryption.ICrypto crypto = org.rx.net.socks.encryption.ICrypto.get(method, pwd, true);
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            ds.setSoTimeout(expectSuccess ? 4000 : 500);
            ByteBuf req = io.netty.buffer.Unpooled.buffer();
            org.rx.net.socks.UdpManager.encode(req, "127.0.0.1", targetPort);
            req.writeBytes("uping".getBytes(StandardCharsets.UTF_8));
            byte[] wire = drainBuf(crypto.encrypt(req));
            ds.send(new java.net.DatagramPacket(wire, wire.length, InetAddress.getByName("127.0.0.1"), ssPort));
            byte[] resp = new byte[512];
            java.net.DatagramPacket pkt = new java.net.DatagramPacket(resp, resp.length);
            if (!expectSuccess) {
                assertThrows(java.net.SocketTimeoutException.class, () -> ds.receive(pkt));
                return;
            }
            ds.receive(pkt);
            ByteBuf dec = crypto.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(pkt.getData(), 0, pkt.getLength()));
            try {
                org.rx.net.socks.UdpManager.decode(dec);
                assertEquals("uping", dec.toString(StandardCharsets.UTF_8));
            } finally {
                dec.release();
            }
        } finally {
            if (crypto instanceof AutoCloseable) ((AutoCloseable) crypto).close();
        }
    }

    @Test
    public void normalizeAndValidateRssConfig_AcceptsShadowUserScopedSocksServers() {
        RssClientConf conf = validRssConf();
        RssClientConf.SocksServer first = new RssClientConf.SocksServer("primary", 1,
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"));
        RssClientConf.SocksServer second = new RssClientConf.SocksServer("backup", 2,
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081"));
        conf.socksServers = Arrays.asList(first, second);
        conf.shadowUsers.get(0).setSocksServers(Collections.singletonList(" backup "));

        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        assertEquals(Collections.singletonList("backup"), conf.shadowUsers.get(0).getSocksServers());
    }

    @Test
    public void fixedUpstreamRequiresAPairedReliableStreamAndBothDatagramMappings() {
        RssClientConf conf = validRssConf();
        RssClientConf.SocksServer spec = conf.socksServers.get(0);
        spec.type = RssClientConf.UpstreamType.UDPCLIENT;
        spec.tcpClient = spec.endpoint;
        spec.udpClient = new InetSocketAddress("127.0.0.1", 4090);
        ShadowUser user = conf.shadowUsers.get(0);
        user.transport = new RssTransport();
        user.transport.profile = "daily";
        user.transport.udpHop = false;
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf));
        spec.adaptiveUdpClient = new InetSocketAddress("127.0.0.1", 4091);
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf));
        user.transport.udpMtu = 1260;
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        user.transport.udpHop = true;
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf));
        user.transport.udpHop = false;
        user.transport.profile = "plain";
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void fixedAndHoppingUsersShareOneRssPeerWithIndependentDatagramRoutes() throws Exception {
        verifySharedRssPeer(false);
    }

    @Test
    public void nestedSocksCarriesTheRssControlStreamAdaptivePathAndOrdinaryDatagrams() throws Exception {
        verifySharedRssPeer(true);
    }

    private void verifySharedRssPeer(boolean nested) throws Exception {
        int[] ports = freeTcpUdpPorts(10);
        int outPort = ports[0];
        int udp2rawPort = ports[1];
        int rpcPort = ports[2];
        int ssPort6896 = ports[3];
        int ssPort6898 = ports[4];
        int dnsPort = ports[5];
        int nsRegPort = ports[6];
        int nsSyncPort = ports[7];
        int inPort = ports[8];

        SocksUser socksUsr = new SocksUser("youfanX");
        socksUsr.setPassword("test-pass");
        org.rx.net.socks.Authenticator auth = (u, p) -> "youfanX".equals(u) && "test-pass".equals(p) ? socksUsr : SocksUser.ANONYMOUS;

        SocksConfig outConf = new SocksConfig(outPort);
        RssServer.configureOutboundConfig(outConf, false);
        outConf.setWhiteListEnabled(false);
        outConf.setAdaptiveTcp(true);
        outConf.setUdpHop(true);
        outConf.setRequiredTcpCipherPorts(new int[0]);
        outConf.setRequiredUdpCipherPorts(new int[0]);
        int hopBase = freeUdpPortRange(6);
        outConf.setDailyPortRange(hopBase + "-" + (hopBase + 5));

        SocksConfig outerConf = new SocksConfig(ports[9]);
        outerConf.setWhiteListEnabled(false);
        SocksProxyServer outer = nested ? new SocksProxyServer(outerConf) : null;
        java.util.Set<Integer> carrierTcp = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<Integer> carrierUdp = java.util.concurrent.ConcurrentHashMap.newKeySet();
        if (outer != null) {
            outer.onTcpRoute.replace((server, ctx) -> {
                carrierTcp.add(ctx.getFirstDestination().getPort());
                ctx.setUpstream(new Upstream(ctx.getFirstDestination()));
            });
            outer.onUdpRoute.replace((server, ctx) -> {
                carrierUdp.add(ctx.getFirstDestination().getPort());
                ctx.setUpstream(new Upstream(ctx.getFirstDestination()));
            });
        }
        outConf.setFixedUdpPort(udp2rawPort);
        SocksProxyServer outSvr = new SocksProxyServer(outConf, auth);
        org.rx.net.rpc.RpcServerConfig rpcServerCfg = new org.rx.net.rpc.RpcServerConfig(
                new org.rx.net.transport.TcpServerConfig(rpcPort));
        rpcServerCfg.getTcpConfig().setTransportFlags(
                org.rx.net.TransportFlags.GFW.flags(org.rx.net.TransportFlags.CIPHER_BOTH).flags());
        rpcServerCfg.setEventComputeVersion(org.rx.net.rpc.RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcServerCfg.getEventBroadcastVersions().add(org.rx.net.socks.SocksRpcContract.RPC_EVENT_VERSION);
        RssRpcApp rpcApp = new RssRpcApp(outSvr);
        AutoCloseable rpcServer = org.rx.net.rpc.Remoting.register(rpcApp, rpcServerCfg);

        ServerSocket tcpEcho = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        java.net.DatagramSocket udpEcho = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawAdaptiveCli = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawAdaptiveSvr = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawUdpCli = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawUdpSvr = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger adaptiveRawC2S = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger adaptiveRawS2C = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger ordinaryRawC2S = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger ordinaryRawS2C = new java.util.concurrent.atomic.AtomicInteger();

        // Start TCP/UDP echo servers + two simulated udp2raw tunnels (4080 -> adaptivePort, 4090 -> 9910).
        startLoopThread(running, () -> {
            java.net.Socket s = tcpEcho.accept();
            Thread w = new Thread(() -> {
                try (java.net.Socket sock = s) {
                    byte[] b = new byte[1024];
                    int n;
                    while ((n = sock.getInputStream().read(b)) != -1) {
                        sock.getOutputStream().write(b, 0, n);
                        sock.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                }
            });
            w.setDaemon(true);
            w.start();
        });
        startLoopThread(running, () -> {
            byte[] b = new byte[2048];
            java.net.DatagramPacket p = new java.net.DatagramPacket(b, b.length);
            udpEcho.receive(p);
            udpEcho.send(new java.net.DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
        });
        int adaptiveServerUdpPort = getAdaptiveUdpPortNumber(outSvr);
        assertTrue(adaptiveServerUdpPort > 0);
        startUdpForwarder(running, rawAdaptiveCli, rawAdaptiveSvr,
                new InetSocketAddress("127.0.0.1", adaptiveServerUdpPort), adaptiveRawC2S, adaptiveRawS2C);
        startUdpForwarder(running, rawUdpCli, rawUdpSvr,
                new InetSocketAddress("127.0.0.1", udp2rawPort),
                ordinaryRawC2S, ordinaryRawS2C);

        RssClientConf oldGlobalConf = RssClient.rssConf;
        RssRuntime runtime = null;
        try {
            RssClientConf conf = new RssClientConf();
            conf.socksPwd = "inner-socks-pwd";
            conf.rpcPort = rpcPort;
            conf.shadowDnsPort = dnsPort;
            conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};
            conf.rpcAutoWhiteListSeconds = 0;
            conf.upstreamHealthCheckSeconds = 60;
            conf.udpLeasePoolEnabled = false;
            conf.tcpCipherPorts = new int[0];
            conf.udpCipherPorts = new int[0];
            conf.defaultRoute = new UserRule();
            conf.defaultRoute.setEnabled(Boolean.TRUE);
            conf.defaultRoute.setRules(Collections.singletonList("default proxy"));
            conf.nameserver = new NameserverConfig();
            conf.nameserver.setRegisterPort(nsRegPort);
            conf.nameserver.setSyncPort(nsSyncPort);

            AuthenticEndpoint mainEp = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", outPort), "youfanX", "test-pass");
            RssClientConf.SocksServer mainServer = new RssClientConf.SocksServer("main", 9, mainEp);
            if (nested) {
                mainServer.type = RssClientConf.UpstreamType.SOCKS5;
                mainServer.rssEndpoint = mainEp;
                mainServer.endpoint = AuthenticEndpoint.valueOf("127.0.0.1:" + ports[9]);
            }
            RssClientConf.SocksServer mainTun = new RssClientConf.SocksServer("main-tun", 1, mainEp);
            mainTun.type = RssClientConf.UpstreamType.UDPCLIENT;
            mainTun.tcpClient = mainEp;
            mainTun.setAdaptiveUdpClient((InetSocketAddress) rawAdaptiveCli.getLocalSocketAddress());
            mainTun.setUdpClient((InetSocketAddress) rawUdpCli.getLocalSocketAddress());
            conf.socksServers = Arrays.asList(mainServer, mainTun);

            ShadowUser u6896 = new ShadowUser();
            u6896.setUsername("rGrey");
            u6896.setSocksUser("rGrey");
            u6896.setSsPort(ssPort6896);
            u6896.setSsPwd("pwd6896");
            u6896.setSsCipher(CipherKind.CHACHA20_IETF_POLY1305.getCipherName());
            u6896.transport = new RssTransport();
            u6896.transport.profile = "daily";
            u6896.transport.udpHop = Boolean.FALSE;
            u6896.transport.udpMtu = 1260;
            u6896.setSocksServers(Collections.singletonList("main-tun"));

            ShadowUser u6898 = new ShadowUser();
            u6898.setUsername("zangshuyuan");
            u6898.setSocksUser("zangshuyuan");
            u6898.setSsPort(ssPort6898);
            u6898.setSsPwd("pwd6898");
            u6898.transport = new RssTransport();
            u6898.transport.profile = "daily";
            u6898.setSocksServers(Collections.singletonList("main"));

            conf.shadowUsers = Arrays.asList(u6896, u6898);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            runtime = new RssRuntime(inPort, conf);

            // TCP connects directly to the shared RSS server,
            //    while Adaptive UDP activates through rawAdaptiveCli (udp2raw 4080 -> 20000).
            org.rx.net.socks.encryption.ICrypto crypto6896 = org.rx.net.socks.encryption.ICrypto.get(
                    CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd6896");
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", ssPort6896)) {
                s.setSoTimeout(5000);
                ByteBuf init = io.netty.buffer.Unpooled.buffer();
                org.rx.net.socks.UdpManager.encode(init, "127.0.0.1", tcpEcho.getLocalPort());
                init.writeBytes("probe-6896".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().write(drainBuf(crypto6896.encrypt(init)));
                s.getOutputStream().flush();
                assertEquals("probe-6896", readDecryptedString(s, crypto6896, 10));

                long deadline = System.currentTimeMillis() + 4000L;
                while (System.currentTimeMillis() < deadline && (adaptiveRawC2S.get() == 0 || adaptiveRawS2C.get() == 0)) {
                    Thread.sleep(30);
                }
                assertTrue(adaptiveRawC2S.get() > 0 && adaptiveRawS2C.get() > 0,
                        "6896 Adaptive UDP PING/PONG must traverse udp2raw tunnel c2s="
                                + adaptiveRawC2S.get() + " s2c=" + adaptiveRawS2C.get());

                int c2sBeforeData = adaptiveRawC2S.get();
                ByteBuf payload = io.netty.buffer.Unpooled.copiedBuffer("adaptive-over-udp2raw-6896", StandardCharsets.UTF_8);
                s.getOutputStream().write(drainBuf(crypto6896.encrypt(payload)));
                s.getOutputStream().flush();
                assertEquals("adaptive-over-udp2raw-6896", readDecryptedString(s, crypto6896, 26));
                assertTrue(adaptiveRawC2S.get() > c2sBeforeData,
                        "6896 TCP data must flow over Adaptive UDP through udp2raw");
            } finally {
                if (crypto6896 instanceof AutoCloseable) ((AutoCloseable) crypto6896).close();
            }

            // The ordinary data plane must pass through its own UDP forwarder in both directions.
            assertSsUdpEcho(ssPort6896, CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd6896",
                    udpEcho.getLocalPort(), true);
            assertTrue(ordinaryRawC2S.get() > 0 && ordinaryRawS2C.get() > 0,
                    "6896 ordinary UDP must traverse udp2raw tunnel c2s="
                            + ordinaryRawC2S.get() + " s2c=" + ordinaryRawS2C.get());

            // 3. 6898 (daily hopping on main) continues working cleanly without touching 6896's udp2raw tunnels.
            int adaptBefore6898 = adaptiveRawC2S.get();
            int ordBefore6898 = ordinaryRawC2S.get();
            if (nested) {
                org.rx.net.socks.encryption.ICrypto crypto = org.rx.net.socks.encryption.ICrypto.get(
                        CipherKind.AES_256_GCM.getCipherName(), "pwd6898");
                try (java.net.Socket stream = new java.net.Socket("127.0.0.1", ssPort6898)) {
                    stream.setSoTimeout(5000);
                    ByteBuf first = io.netty.buffer.Unpooled.buffer();
                    org.rx.net.socks.UdpManager.encode(first, "127.0.0.1", tcpEcho.getLocalPort());
                    first.writeBytes("first".getBytes(StandardCharsets.UTF_8));
                    stream.getOutputStream().write(drainBuf(crypto.encrypt(first)));
                    assertEquals("first", readDecryptedString(stream, crypto, 5));
                    long deadline = System.currentTimeMillis() + 5000;
                    do {
                        ByteBuf body = io.netty.buffer.Unpooled.copiedBuffer("round-trip", StandardCharsets.UTF_8);
                        stream.getOutputStream().write(drainBuf(crypto.encrypt(body)));
                        assertEquals("round-trip", readDecryptedString(stream, crypto, 10));
                        Thread.sleep(50);
                    } while (!carrierUdp.contains(hopBase) && !carrierUdp.contains(hopBase + 1)
                            && System.currentTimeMillis() < deadline);
                } finally {
                    if (crypto instanceof AutoCloseable) ((AutoCloseable) crypto).close();
                }
            } else {
                assertSsTcpEcho(ssPort6898, CipherKind.AES_256_GCM.getCipherName(), "pwd6898", tcpEcho.getLocalPort(), true);
            }
            assertSsUdpEcho(ssPort6898, CipherKind.AES_256_GCM.getCipherName(), "pwd6898", udpEcho.getLocalPort(), true);
            assertEquals(adaptBefore6898, adaptiveRawC2S.get(), "6898 must not use 6896 adaptive udp2raw tunnel");
            assertEquals(ordBefore6898, ordinaryRawC2S.get(), "6898 must not use 6896 ordinary udp2raw tunnel");
            if (nested) {
                assertTrue(carrierTcp.contains(rpcPort), "RPC must cross the outer CONNECT");
                assertTrue(carrierTcp.contains(outPort), "Reliable TCP must cross the outer CONNECT");
                assertTrue(carrierUdp.contains(hopBase) || carrierUdp.contains(hopBase + 1),
                        "Adaptive UDP must cross the outer UDP ASSOCIATE: " + carrierUdp);
                assertTrue(carrierUdp.stream().anyMatch(p -> p >= hopBase + 2 && p <= hopBase + 5),
                        "Ordinary datagrams must cross the outer UDP ASSOCIATE: " + carrierUdp);
            }
        } finally {
            running.set(false);
            if (runtime != null) runtime.close();
            RssClient.rssConf = oldGlobalConf;
            tcpEcho.close();
            udpEcho.close();
            rawAdaptiveCli.close();
            rawAdaptiveSvr.close();
            rawUdpCli.close();
            rawUdpSvr.close();
            rpcServer.close();
            outSvr.close();
            if (outer != null) outer.close();
        }
    }

    private static int freeUdpPortRange(int count) throws Exception {
        for (int base = 24000; base < 60000; base += count) {
            java.util.List<java.net.DatagramSocket> opened = new java.util.ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    opened.add(new java.net.DatagramSocket(base + i));
                }
                return base;
            } catch (java.net.BindException ignored) {
            } finally {
                for (java.net.DatagramSocket s : opened) {
                    s.close();
                }
            }
        }
        throw new IllegalStateException("No free UDP port range of size " + count);
    }

    private static int getAdaptiveUdpPortNumber(SocksProxyServer server) throws Exception {
        java.lang.reflect.Field f = SocksProxyServer.class.getDeclaredField("adaptivePort");
        f.setAccessible(true);
        org.rx.net.rss.tcp.AdaptiveUdpPort port = (org.rx.net.rss.tcp.AdaptiveUdpPort) f.get(server);
        return port == null ? 0 : port.port();
    }

    private static void startLoopThread(java.util.concurrent.atomic.AtomicBoolean running, org.rx.util.function.Action body) {
        Thread t = new Thread(() -> {
            while (running.get()) {
                try {
                    body.invoke();
                } catch (Throwable ignored) {
                    if (!running.get()) break;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void startUdpForwarder(java.util.concurrent.atomic.AtomicBoolean running,
                                          java.net.DatagramSocket cliSide,
                                          java.net.DatagramSocket svrSide,
                                          InetSocketAddress serverTarget,
                                          java.util.concurrent.atomic.AtomicInteger c2sCount,
                                          java.util.concurrent.atomic.AtomicInteger s2cCount) throws Exception {
        cliSide.setSoTimeout(200);
        svrSide.setSoTimeout(200);
        java.util.concurrent.atomic.AtomicReference<InetSocketAddress> lastCli = new java.util.concurrent.atomic.AtomicReference<>();
        startLoopThread(running, () -> {
            byte[] buf = new byte[4096];
            java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
            try {
                cliSide.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                return;
            }
            lastCli.set((InetSocketAddress) p.getSocketAddress());
            c2sCount.incrementAndGet();
            svrSide.send(new java.net.DatagramPacket(p.getData(), p.getLength(), serverTarget));
        });
        startLoopThread(running, () -> {
            byte[] buf = new byte[4096];
            java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
            try {
                svrSide.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                return;
            }
            InetSocketAddress cli = lastCli.get();
            if (cli != null) {
                s2cCount.incrementAndGet();
                cliSide.send(new java.net.DatagramPacket(p.getData(), p.getLength(), cli));
            }
        });
    }

    @Test
    public void weightedRoutesMayMixNativeAndStandardSocksForPlainUsers() {
        RssClientConf conf = validRssConf();
        RssClientConf.SocksServer standard = new RssClientConf.SocksServer("standard", 2,
                AuthenticEndpoint.valueOf("127.0.0.1:1081"));
        standard.type = RssClientConf.UpstreamType.SOCKS5;
        conf.socksServers = Arrays.asList(conf.socksServers.get(0), standard);
        ShadowUser user = conf.shadowUsers.get(0);
        user.setSocksServers(Arrays.asList("primary", "standard"));
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        user.transport = new RssTransport();
        user.transport.profile = "game";
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void normalizeAndValidateRssConfig_RejectsUnknownShadowUserSocksServer() {
        RssClientConf conf = validRssConf();
        conf.socksServers.get(0).setId("primary");
        conf.shadowUsers.get(0).setSocksServers(Collections.singletonList("missing"));

        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void normalizeAndValidateRssConfig_RejectsShadowUserScopedDisabledSocksServers() {
        RssClientConf conf = validRssConf();
        RssClientConf.SocksServer disabled = new RssClientConf.SocksServer("disabled", 0,
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"));
        conf.socksServers = Collections.singletonList(disabled);
        conf.shadowUsers.get(0).setSocksServers(Collections.singletonList("disabled"));

        assertTrue(!RssClient.normalizeAndValidateRssConfig(conf));
    }

    @Test
    public void buildUserSocksServers_UsesOnlyScopedWeights() {
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSocksServers(Arrays.asList("a", "c"));
        UpstreamSupport a = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"), null);
        a.setConfiguredWeight(1);
        UpstreamSupport b = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081"), null);
        b.setConfiguredWeight(2);
        UpstreamSupport c = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1082"), null);
        c.setConfiguredWeight(3);
        Map<String, UpstreamSupport> supportByServerId = new LinkedHashMap<>();
        supportByServerId.put("a", a);
        supportByServerId.put("b", b);
        supportByServerId.put("c", c);

        RandomList<UpstreamSupport> scoped = RssClient.buildUserSocksServers(
                Collections.singletonList(user), supportByServerId).get("ss-rocky");

        assertTrue(scoped.contains(a));
        assertTrue(!scoped.contains(b));
        assertTrue(scoped.contains(c));
        assertEquals(1, scoped.getWeight(a));
        assertEquals(3, scoped.getWeight(c));
    }

    @Test
    public void resolveUserSocksServers_UsesShadowUserScopedList() {
        UpstreamSupport first = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"), null);
        first.setConfiguredWeight(1);
        UpstreamSupport second = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081"), null);
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
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClientConf conf = new RssClientConf();
            conf.upstreamHealthFailureThreshold = 1;
            RssClient.rssConf = conf;
            UpstreamSupport support = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"), null);
            support.setConfiguredWeight(1);
            RandomList<UpstreamSupport> defaults = new RandomList<>();
            defaults.add(support, 1);
            RandomList<UpstreamSupport> userOnly = new RandomList<>();
            userOnly.add(support, 1);
            Map<String, RandomList<UpstreamSupport>> userServers = new LinkedHashMap<>();
            userServers.put("ss-rocky", userOnly);
            RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(defaults,
                    new RandomList<DnsResolveInterceptor>(), userServers, null);

            RssClient.updateUpstreamHealth(snapshot, defaults, support, false, true);

            assertEquals(0, defaults.getWeight(support));
            assertEquals(0, userOnly.getWeight(support));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void updateUpstreamHealth_RequiresConsecutiveFailuresBeforeWeightZero() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClientConf conf = new RssClientConf();
            conf.upstreamHealthFailureThreshold = 3;
            RssClient.rssConf = conf;
            UpstreamSupport support = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"), null);
            support.setConfiguredWeight(1);
            RandomList<UpstreamSupport> defaults = new RandomList<>();
            defaults.add(support, 1);
            RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(defaults,
                    new RandomList<DnsResolveInterceptor>(), null, null);

            RssClient.updateUpstreamHealth(snapshot, defaults, support, false, true);
            RssClient.updateUpstreamHealth(snapshot, defaults, support, false, true);

            assertTrue(support.isHealthy());
            assertEquals(2, support.getHealthFailureCount());
            assertEquals(1, defaults.getWeight(support));

            RssClient.updateUpstreamHealth(snapshot, defaults, support, false, true);

            assertTrue(!support.isHealthy());
            assertEquals(3, support.getHealthFailureCount());
            assertEquals(0, defaults.getWeight(support));

            RssClient.updateUpstreamHealth(snapshot, defaults, support, true, true);

            assertTrue(support.isHealthy());
            assertEquals(0, support.getHealthFailureCount());
            assertEquals(1, defaults.getWeight(support));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void upstreamHealthCheckPeriodMillis_UsesConfiguredSeconds() {
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClientConf conf = new RssClientConf();
            conf.upstreamHealthCheckSeconds = 7;
            RssClient.rssConf = conf;

            assertEquals(7000L, RssClient.upstreamHealthCheckPeriodMillis());

            conf.upstreamHealthCheckSeconds = 0;
            assertEquals(1000L, RssClient.upstreamHealthCheckPeriodMillis());
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void pingUpstream_ReturnsTrueForLocalRssRpcFacadeAndRestoresWeight() throws Exception {
        int rpcPort = freePort();
        SocksProxyServer backSvr = new SocksProxyServer(new SocksConfig(new LocalAddress("RSS_HEALTH_RPC_BACK")));
        TcpServer rpcServer = null;
        SocksRpcContract facade = null;
        RssClientConf oldConf = RssClient.rssConf;
        try {
            RssClientConf conf = new RssClientConf();
            conf.upstreamHealthFailureThreshold = 3;
            RssClient.rssConf = conf;
            RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(rpcPort));
            rpcServerConf.getHybridConfig().setEnableUdpDirect(false);
            rpcServerConf.getHybridConfig().setEnableUdpHolePunch(false);
            rpcServer = Remoting.register(new RssRpcApp(backSvr), rpcServerConf);

            RpcClientConfig<SocksRpcContract> rpcClientConf = RpcClientConfig.poolMode(
                    new InetSocketAddress("127.0.0.1", rpcPort), 1, 1);
            rpcClientConf.getHybridConfig().setEnableUdpDirect(false);
            rpcClientConf.getHybridConfig().setEnableUdpHolePunch(false);
            facade = Remoting.createFacade(SocksRpcContract.class, rpcClientConf);
            assertTrue(Remoting.ping(facade, 1000));

            UpstreamSupport support = new UpstreamSupport(AuthenticEndpoint.valueOf("u:p@127.0.0.1:1080"), facade);
            support.setConfiguredWeight(1);
            support.setHealthy(false);
            support.setHealthFailureCount(3);
            RandomList<UpstreamSupport> servers = new RandomList<>();
            servers.add(support, 0);
            RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(servers,
                    new RandomList<DnsResolveInterceptor>(), null, null);

            assertTrue(RssClient.pingUpstream(support));
            RssClient.updateUpstreamHealth(snapshot, servers, support, true, true);

            assertTrue(support.isHealthy());
            assertEquals(0, support.getHealthFailureCount());
            assertEquals(1, servers.getWeight(support));
        } finally {
            RssClient.rssConf = oldConf;
            if (facade != null) {
                facade.close();
            }
            if (rpcServer != null) {
                rpcServer.close();
            }
            backSvr.close();
        }
    }

    @Test
    public void addWhiteList_IncludesRpcPeerWhenReportedPublicIpDiffers() throws Exception {
        int rpcPort = freePort();
        CopyOnWriteArraySet<InetAddress> primaryAllowed = new CopyOnWriteArraySet<>();
        SocksConfig primaryConfig = new SocksConfig(new LocalAddress("RSS_WHITELIST_PRIMARY")) {
            @Override
            public void allowWhiteList(InetAddress endpoint) {
                primaryAllowed.add(endpoint);
            }
        };
        SocksProxyServer primary = new SocksProxyServer(primaryConfig);
        TcpServer rpcServer = null;
        SocksRpcContract facade = null;
        try {
            RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(rpcPort));
            serverConfig.getHybridConfig().setEnableUdpDirect(false);
            serverConfig.getHybridConfig().setEnableUdpHolePunch(false);
            rpcServer = Remoting.register(new RssRpcApp(primary), serverConfig);

            RpcClientConfig<SocksRpcContract> clientConfig = RpcClientConfig.poolMode(
                    new InetSocketAddress("127.0.0.1", rpcPort), 1, 1);
            clientConfig.getHybridConfig().setEnableUdpDirect(false);
            clientConfig.getHybridConfig().setEnableUdpHolePunch(false);
            facade = Remoting.createFacade(SocksRpcContract.class, clientConfig);

            InetAddress reportedAddress = InetAddress.getByName("192.0.2.1");
            facade.addWhiteList(reportedAddress, SocksRpcContract.rpcToken());

            assertTrue(primaryAllowed.contains(reportedAddress));
            assertTrue(primaryAllowed.stream().anyMatch(InetAddress::isLoopbackAddress));
        } finally {
            if (facade != null) {
                facade.close();
            }
            if (rpcServer != null) {
                rpcServer.close();
            }
            primary.close();
        }
    }

    @Test
    public void inboundConfigUsesNativeUdpAndConfiguredTimeouts() {
        RssClientConf conf = new RssClientConf();
        SocksConfig config = new SocksConfig(new LocalAddress("RSS_IN_NORMAL_CONF"));
        RssClient.configureInboundConfig(conf, config);
        assertFalse(config.isEnableUdp2raw());
        assertEquals(0, config.getUdpMtu());
        assertEquals(conf.tcpTimeoutSeconds, config.getTcpIdleTimeoutSeconds());
        assertEquals(conf.udpTimeoutSeconds, config.getUdpReadTimeoutSeconds());
    }

    @Test
    public void rssServerConfigureOutboundConfig_AppliesUdpMtu() {
        SocksConfig config = new SocksConfig(new LocalAddress("RSS_SERVER_OUT_CONF"));

        RssServer.configureOutboundConfig(config, true);

        assertTrue(config.isDebug());
        assertEquals(0, config.getReadTimeoutSeconds());
        assertEquals(0, config.getWriteTimeoutSeconds());
        assertEquals(240, config.getTcpIdleTimeoutSeconds());
        assertEquals(SocksConfig.DEF_UDP_READ_TIMEOUT_SECONDS, config.getUdpReadTimeoutSeconds());
        assertEquals(0, config.getUdpMtu());
        assertEquals(1300, config.getDailyUdpMtu());
        assertEquals(1300, config.resolveDailyUdpMtu());
        assertFalse(config.isEnableUdp2raw());
        assertTrue(config.isDailyUdp());
        assertEquals(1, config.getUdpRedundantMultiplier());
        assertFalse(config.isUdpCompressEnabled());
    }

    @Test
    public void socksServerJsonReader_RequiresObjectFormatWithWeight() {
        RssClientConf conf = JSON.parseObject("{\"socksServers\":["
                + "{\"id\":\"backup\",\"weight\":2,\"endpoint\":\"u:p@127.0.0.1:1081\"}]}", RssClientConf.class);

        assertEquals(1, conf.socksServers.size());
        assertEquals("backup", conf.socksServers.get(0).getId());
        assertEquals(2, RssClient.weightOf(conf.socksServers.get(0)));
        assertEquals(1081, conf.socksServers.get(0).getEndpoint().requireEndpoint().getPort());
        assertThrows(Exception.class, () -> JSON.parseObject(
                "{\"socksServers\":[\"u:p@127.0.0.1:1080\"]}", RssClientConf.class));
    }

    @Test
    public void socksServerJsonReaderRejectsUnknownFieldsAndUnsupportedType() {
        String start = "{\"socksServers\":[{\"id\":\"tun\",\"weight\":1,\"endpoint\":\"127.0.0.1:1081\",";
        assertThrows(Exception.class, () -> JSON.parseObject(start + "\"udp2rawClient\":\"127.0.0.1:4095\"}]}", RssClientConf.class));
        assertThrows(Exception.class, () -> JSON.parseObject(start + "\"type\":\"tcp-only\"}]}", RssClientConf.class));
    }

    @Test
    public void standardProxyHasNoPrivateCodecsAndRejectsProtectedTraffic() {
        RssClientConf conf = validRssConf();
        conf.egress.tcpCodec = "zlib-stored";
        SocksConfig standard = RssClient.standardSocksConfig(conf);
        UpstreamSupport peer = new UpstreamSupport(conf.socksServers.get(0).endpoint, null);
        peer.setStandardSocks5(true);
        SocksConfig profile = new SocksConfig();
        assertSame(standard, RssClient.outboundForRoute(profile, standard, peer, true));
        assertEquals(org.rx.net.RssEgressCodec.NONE, standard.getRssEgressCodec());
        profile.setTransportFlags(org.rx.net.TransportFlags.RSS_PSK_AES.flags());
        assertThrows(Exception.class, () -> RssClient.outboundForRoute(profile, standard, peer, true));
        profile.setTransportFlags(org.rx.net.TransportFlags.NONE.flags());
        profile.setDailyUdpFeatures(org.rx.net.rss.udp.RssDatagramHandler.AES);
        assertThrows(Exception.class, () -> RssClient.outboundForRoute(profile, standard, peer, false));
    }

    @Test
    public void configureOutboundConfig_RefreshesTimeoutAndUdpLeaseSettings() {
        RssClientConf conf = new RssClientConf();
        conf.connectTimeoutSeconds = 2;
        conf.tcpTimeoutSeconds = 3;
        conf.udpTimeoutSeconds = 4;
        conf.udpLeasePoolEnabled = true;
        conf.udpLeasePoolMinSize = 1;
        conf.udpLeasePoolMaxSize = 5;
        SocksConfig config = new SocksConfig();

        RssClient.configureOutboundConfig(conf, config);

        assertEquals(2000, config.getConnectTimeoutMillis());
        assertEquals(0, config.getReadTimeoutSeconds());
        assertEquals(0, config.getWriteTimeoutSeconds());
        assertEquals(3, config.getTcpIdleTimeoutSeconds());
        assertEquals(4, config.getUdpReadTimeoutSeconds());
        assertEquals(0, config.getUdpMtu());
        assertEquals(1300, config.getDailyUdpMtu());
        assertEquals(1300, config.resolveDailyUdpMtu());
        assertTrue(config.isUdpLeasePoolEnabled());
        assertEquals(5, config.getUdpLeasePoolMaxSize());

        conf.udpMtu = 1420;
        RssClient.configureOutboundConfig(conf, config);
        assertEquals(1420, config.getUdpMtu());
        assertEquals(1420, config.getDailyUdpMtu());
    }

    @Test
    public void localTransportPoolsSeparateShadowUsersOnTheSameListener() {
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 9999);
        AuthenticEndpoint a = RssClient.resolveShadowEndpoint(addr, "a");
        AuthenticEndpoint b = RssClient.resolveShadowEndpoint(addr, "b");
        SocksConfig config = new SocksConfig();
        assertNotEquals(org.rx.net.socks.TcpWarmPoolKey.from(a, config, null),
                org.rx.net.socks.TcpWarmPoolKey.from(b, config, null));
        assertNotEquals(org.rx.net.socks.UdpLeasePoolKey.from(a, config, null),
                org.rx.net.socks.UdpLeasePoolKey.from(b, config, null));
    }

    @Test
    public void transportProfilesFollowUserAcrossReloadAndSharedSocksCredentials() {
        String json = "{\"udpCipherPorts\":[5353],\"shadowUsers\":[{\"ssPort\":6898,\"username\":\"a\",\"socksUser\":\"shared\","
                + "\"transport\":{\"profile\":\"daily\"}},{\"ssPort\":6899,\"username\":\"b\",\"socksUser\":\"shared\"}]}";
        RssClientConf conf = JSON.parseObject(json, RssClientConf.class);
        SocksConfig inbound = new SocksConfig();
        RssClient.OutboundProfiles profiles = new RssClient.OutboundProfiles(conf, inbound);
        ShadowUser a = conf.shadowUsers.get(0), b = conf.shadowUsers.get(1);
        assertEquals(0, RssTransport.udpCipherPorts().length);
        assertFalse(RssTransport.validPortLists(new int[]{53}, new int[]{443}));
        assertFalse(RssTransport.validPortLists(new int[]{80}, new int[]{53}));
        assertThrows(IllegalArgumentException.class, () -> new RssPortPolicy(new int[]{53}, new int[]{443}));
        assertThrows(IllegalArgumentException.class, () -> new RssPortPolicy(new int[]{80}, new int[]{53}));
        assertTrue(profiles.tcp(a, 443).isAdaptiveTcp());
        assertEquals(0, profiles.tcp(a, 443).getTransportFlags().getValue());
        assertFalse(profiles.tcp(b, 12345).isAdaptiveTcp());
        assertTrue(profiles.tcp(a, 12345).getTransportFlags().has(TransportFlags.COMPRESS_BOTH));
        assertSame(profiles.tcp(null, 443), profiles.tcp(b, 12345));
        assertTrue(profiles.tcp(b, 80).getTransportFlags().has(TransportFlags.RSS_PSK_AES));
        assertTrue(profiles.tcp(a, 80).getTransportFlags().has(TransportFlags.RSS_PSK_AES));
        assertFalse(profiles.tcp(a, 80).getTransportFlags().has(TransportFlags.COMPRESS_WRITE));
        assertEquals(5, profiles.udp(a, 80).getDailyUdpFeatures());
        assertEquals(0, profiles.udp(b, 80).getDailyUdpFeatures());
        assertEquals(7, profiles.udp(a, 5353).getDailyUdpFeatures());
        assertEquals(2, profiles.udp(b, 5353).getDailyUdpFeatures());

        RssClientConf reload = JSON.parseObject(json, RssClientConf.class);
        RssTransport policy = reload.shadowUsers.get(0).transport;
        policy.udp = 3;
        assertFalse(policy.valid());
        policy.udp = 0;
        policy.tcp = 1;
        for (int fec = 0; fec < 3; fec++) {
            policy.fec = fec;
            assertFalse(policy.valid());
            assertThrows(IllegalArgumentException.class, () -> new RssClient.OutboundProfiles(reload, inbound));
        }
        policy.fec = 3;
        RssClient.OutboundProfiles changedDaily = new RssClient.OutboundProfiles(reload, inbound);
        assertEquals(4, changedDaily.udp(a, 80).getDailyUdpFeatures());
        assertEquals(4, changedDaily.udp(a, 443).getDailyUdpFeatures());
        policy.fec = policy.tcp = policy.udp = null;
        for (String name : new String[]{"game", "weak"}) {
            policy.profile = name;
            policy.tcpCompressionLevel = 6;
            RssClient.OutboundProfiles changed = new RssClient.OutboundProfiles(reload, inbound);
            boolean game = "game".equals(name);
            SocksConfig fast = changed.tcp(a, 12345);
            assertEquals(game ? SocksConfig.ADAPTIVE_GAME : SocksConfig.ADAPTIVE_WEAK, fast.getAdaptiveProfile());
            assertEquals(6, fast.getTcpCompressionLevel());
            assertTrue(fast.isAdaptiveTcp());
            assertFalse(changed.tcp(a, 80).getTransportFlags().has(TransportFlags.COMPRESS_WRITE));
            assertTrue(changed.tcp(a, 80).getTransportFlags().has(TransportFlags.RSS_PSK_AES));
            assertEquals(0, changed.tcp(a, 443).getTransportFlags().getValue());
            int udpFeatures = game ? 9 : 21;
            assertEquals(udpFeatures, changed.udp(a, 80).getDailyUdpFeatures());
            assertEquals(udpFeatures | 2, changed.udp(a, 5353).getDailyUdpFeatures());
            assertEquals(udpFeatures & ~1, changed.udp(a, 443).getDailyUdpFeatures());
        }
        policy.tcpCompressionLevel = 10;
        assertFalse(policy.valid());
        policy.tcpCompressionLevel = null;
        policy.profile = "game";
        policy.fec = 1;
        assertFalse(policy.valid());
        policy.profile = "unknown";
        assertThrows(IllegalArgumentException.class, () -> new RssClient.OutboundProfiles(reload, inbound));
        reload.tcpCipherPorts = new int[]{443};
        assertFalse(RssTransport.validPortLists(reload.tcpCipherPorts, reload.tcpCodecBypassPorts));

        String noHopJson = "{\"shadowUsers\":["
                + "{\"ssPort\":6896,\"username\":\"grey\",\"socksUser\":\"shared\",\"ssCipher\":\"chacha20-ietf-poly1305\","
                + "\"transport\":{\"profile\":\"daily\",\"udpHop\":false}},"
                + "{\"ssPort\":6898,\"username\":\"main\",\"socksUser\":\"shared\",\"transport\":{\"profile\":\"daily\"}}]}";
        RssClientConf noHopConf = JSON.parseObject(noHopJson, RssClientConf.class);
        RssClient.OutboundProfiles isolated = new RssClient.OutboundProfiles(noHopConf, inbound);
        ShadowUser grey = noHopConf.shadowUsers.get(0), main = noHopConf.shadowUsers.get(1);
        assertEquals(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), grey.cipherName());
        assertEquals(CipherKind.AES_256_GCM.getCipherName(), main.cipherName());
        assertTrue(isolated.tcp(grey, 12345).isAdaptiveTcp());
        assertTrue(isolated.tcp(main, 12345).isAdaptiveTcp());
        assertFalse(isolated.tcp(grey, 12345).isUdpHop());
        assertTrue(isolated.tcp(main, 12345).isUdpHop());
        org.junit.jupiter.api.Assertions.assertNotSame(isolated.tcp(grey, 12345), isolated.tcp(main, 12345));
        assertTrue(isolated.tcp(grey, 12345).getTransportFlags().has(TransportFlags.COMPRESS_BOTH));
        assertEquals(5, isolated.udp(grey, 80).getDailyUdpFeatures());
        assertEquals(5, isolated.udp(main, 80).getDailyUdpFeatures());
        assertFalse(isolated.udp(grey, 80).isUdpHop());
        assertTrue(isolated.udp(main, 80).isUdpHop());
        org.junit.jupiter.api.Assertions.assertNotSame(isolated.udp(grey, 80), isolated.udp(main, 80));
        assertEquals(0, isolated.udp(grey, 80).getUdpMtu());
        assertEquals(1300, isolated.udp(grey, 80).resolveDailyUdpMtu());
        assertEquals(0, profiles.udp(b, 80).getUdpMtu());
        assertEquals(1300, profiles.udp(b, 5353).resolveDailyUdpMtu());

        String mtuJson = "{\"shadowUsers\":["
                + "{\"ssPort\":6896,\"username\":\"plainCustom\",\"socksUser\":\"shared\",\"transport\":{\"profile\":\"plain\",\"udpMtu\":1240}},"
                + "{\"ssPort\":6897,\"username\":\"dailyCustom\",\"socksUser\":\"shared\",\"transport\":{\"profile\":\"daily\",\"udpMtu\":1400}}]}";
        RssClientConf mtuConf = JSON.parseObject(mtuJson, RssClientConf.class);
        assertTrue( mtuConf.shadowUsers.get(0).transport.valid());
        RssClient.OutboundProfiles mtuProfiles = new RssClient.OutboundProfiles(mtuConf, inbound);
        assertEquals(1240, mtuProfiles.udp(mtuConf.shadowUsers.get(0), 80).getUdpMtu());
        assertEquals(0, mtuProfiles.udp(mtuConf.shadowUsers.get(1), 80).getUdpMtu());
        assertEquals(1400, mtuProfiles.udp(mtuConf.shadowUsers.get(1), 80).resolveDailyUdpMtu());
        mtuConf.shadowUsers.get(0).transport.udpMtu = 100;
        assertFalse(mtuConf.shadowUsers.get(0).transport.valid());
    }

    @Test
    public void rssTcpIdleConfig_ClonesAndCanBeDisabled() {
        RssClientConf conf = new RssClientConf();
        SocksConfig inbound = new SocksConfig();
        RssClient.configureInboundConfig(conf, inbound);
        SocksConfig outbound = RssClient.createOutboundConfig(conf, inbound);
        assertEquals(120, outbound.getTcpIdleTimeoutSeconds());
        assertEquals(0, outbound.getReadTimeoutSeconds());
        assertEquals(0, outbound.getWriteTimeoutSeconds());

        conf.tcpTimeoutSeconds = 0;
        RssClient.configureInboundConfig(conf, inbound);
        RssClient.configureOutboundConfig(conf, outbound);
        assertEquals(0, inbound.getTcpIdleTimeoutSeconds());
        assertEquals(0, outbound.getTcpIdleTimeoutSeconds());
        assertEquals(conf.udpTimeoutSeconds, outbound.getUdpReadTimeoutSeconds());
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
        RssClientConf oldConf = validRssConf();
        RssClientConf newConf = validRssConf();

        assertTrue(!RssClient.inServerRestartRequired(oldConf, newConf));

        newConf = validRssConf();
        newConf.connectTimeoutSeconds = oldConf.connectTimeoutSeconds + 1;
        assertTrue(RssClient.inServerRestartRequired(oldConf, newConf));

        newConf = validRssConf();
        newConf.udpTimeoutSeconds = oldConf.udpTimeoutSeconds + 1;
        assertTrue(RssClient.inServerRestartRequired(oldConf, newConf));

        newConf = validRssConf();
        RssClientConf.SocksServer udp2raw = new RssClientConf.SocksServer("udp2raw", 1,
                AuthenticEndpoint.valueOf("u:p@127.0.0.1:9910"));
        udp2raw.type = RssClientConf.UpstreamType.UDPCLIENT;
        udp2raw.setTcpClient(AuthenticEndpoint.valueOf("127.0.0.1:4093"));
        newConf.socksServers = Arrays.asList(newConf.socksServers.get(0), udp2raw);
        assertFalse(RssClient.inServerRestartRequired(oldConf, newConf));
    }

    @Test
    public void shadowServerRestartRequired_DetectsConfigObjectChanges() {
        RssClientConf oldConf = validRssConf();
        RssClientConf newConf = validRssConf();

        assertTrue(!RssClient.shadowServerRestartRequired(oldConf, newConf));

        newConf.logFlags = oldConf.logFlags + 1;
        assertTrue(RssClient.shadowServerRestartRequired(oldConf, newConf));
    }

    private RssClientConf validRssConf() {
        RssClientConf conf = new RssClientConf();
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(2081);
        user.setSsPwd("pwd");
        user.setSocksUser("inner");
        conf.shadowUsers = Collections.singletonList(user);
        conf.socksPwd = "socks-pwd";
        AuthenticEndpoint endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "p");
        conf.socksServers = Collections.singletonList(new RssClientConf.SocksServer("primary", 1, endpoint));
        return conf;
    }

    @Test
    public void perRouteOptInEgressCodecParsingValidationAndIsolation() {
        String validJson = "{\"egress\":{\"tcpCodec\":\"none\",\"udpCodec\":\"none\"},\"socksServers\":["
                + "{\"id\":\"stored\",\"weight\":1,\"endpoint\":\"u:p@127.0.0.1:1081\",\"egress\":{\"tcpCodec\":\"zlib-stored\",\"udpCodec\":\"none\"}},"
                + "{\"id\":\"plain\",\"weight\":1,\"endpoint\":\"u:p@127.0.0.1:1082\",\"egress\":{\"tcpCodec\":\"none\"}},"
                + "{\"id\":\"nested\",\"type\":\"socks5\",\"weight\":1,\"endpoint\":\"127.0.0.1:1089\",\"rssEndpoint\":\"u:p@127.0.0.1:1083\",\"egress\":{\"tcpCodec\":\"zlib-stored\"}}"
                + "]}";
        RssClientConf parsed = JSON.parseObject(validJson, RssClientConf.class);
        assertEquals("zlib-stored", parsed.socksServers.get(0).egress.tcpCodec);
        assertEquals("none", parsed.socksServers.get(1).egress.tcpCodec);
        assertEquals("zlib-stored", parsed.socksServers.get(2).egress.tcpCodec);

        assertThrows(Exception.class, () -> JSON.parseObject(
                "{\"socksServers\":[{\"id\":\"bad\",\"weight\":1,\"endpoint\":\"u:p@127.0.0.1:1081\",\"egress\":{\"unknown\":1}}]}",
                RssClientConf.class));

        RssClientConf badUdp = validRssConf();
        badUdp.socksServers.get(0).egress = new RssClientConf.Egress();
        badUdp.socksServers.get(0).egress.udpCodec = "zlib-stored";
        assertFalse(RssClient.normalizeAndValidateRssConfig(badUdp));

        RssClientConf badStd = validRssConf();
        badStd.socksServers.get(0).type = RssClientConf.UpstreamType.SOCKS5;
        badStd.socksServers.get(0).egress = new RssClientConf.Egress();
        badStd.socksServers.get(0).egress.tcpCodec = "zlib-stored";
        assertFalse(RssClient.normalizeAndValidateRssConfig(badStd));

        RssClientConf ok = validRssConf();
        RssClientConf.SocksServer s1 = new RssClientConf.SocksServer("stored", 1, AuthenticEndpoint.valueOf("u:p@127.0.0.1:1081"));
        s1.egress = new RssClientConf.Egress();
        s1.egress.tcpCodec = "zlib-stored";
        RssClientConf.SocksServer s2 = new RssClientConf.SocksServer("raw", 1, AuthenticEndpoint.valueOf("u:p@127.0.0.1:1082"));
        ok.socksServers = Arrays.asList(s1, s2);
        assertTrue(RssClient.normalizeAndValidateRssConfig(ok));

        RssRuntime.UpstreamSnapshot snapshot = RssClient.buildUpstreams(ok);
        try {
            UpstreamSupport storedPeer = null, rawPeer = null;
            for (UpstreamSupport s : snapshot.socksServers.readOnlySnapshot()) {
                if ("stored".equals(s.getRouteId())) storedPeer = s;
                if ("raw".equals(s.getRouteId())) rawPeer = s;
            }
            assertNotNull(storedPeer);
            assertNotNull(rawPeer);
            assertEquals(org.rx.net.RssEgressCodec.ZLIB_STORED, storedPeer.getRssEgressCodec());
            assertEquals(org.rx.net.RssEgressCodec.NONE, rawPeer.getRssEgressCodec());

            SocksConfig base = RssClient.createOutboundConfig(ok, new SocksConfig());
            SocksConfig standard = RssClient.standardSocksConfig(ok);
            SocksConfig boundStored1 = RssClient.outboundForRoute(base, standard, storedPeer, true);
            SocksConfig boundStored2 = RssClient.outboundForRoute(base, standard, storedPeer, true);
            SocksConfig boundRaw = RssClient.outboundForRoute(base, standard, rawPeer, true);
            assertSame(boundStored1, boundStored2, "route egress config must be cached with zero per-connection allocation");
            assertEquals(org.rx.net.RssEgressCodec.ZLIB_STORED, boundStored1.getRssEgressCodec());
            assertEquals(org.rx.net.RssEgressCodec.NONE, boundRaw.getRssEgressCodec());
            assertSame(base, boundRaw);
            assertNotEquals(
                    org.rx.net.socks.TcpWarmPoolKey.from(storedPeer.getEndpoint(), boundStored1, "u"),
                    org.rx.net.socks.TcpWarmPoolKey.from(storedPeer.getEndpoint(), boundRaw, "u"));

            // Per-user transport.egress overrides top-level default (e.g. plain=zlib-stored, daily=none).
            String perUserJson = "{\"egress\":{\"tcpCodec\":\"none\"},\"shadowUsers\":["
                    + "{\"ssPort\":6899,\"username\":\"p\",\"socksUser\":\"p\",\"transport\":{\"profile\":\"plain\",\"egress\":{\"tcpCodec\":\"zlib-stored\"}}},"
                    + "{\"ssPort\":6898,\"username\":\"d\",\"socksUser\":\"d\",\"transport\":{\"profile\":\"daily\",\"egress\":{\"tcpCodec\":\"none\"}}}]}";
            RssClientConf perUser = JSON.parseObject(perUserJson, RssClientConf.class);
            RssClient.OutboundProfiles perUserProfiles = new RssClient.OutboundProfiles(perUser, new SocksConfig());
            assertEquals(org.rx.net.RssEgressCodec.ZLIB_STORED, perUserProfiles.tcp(perUser.shadowUsers.get(0), 443).getRssEgressCodec());
            assertEquals(org.rx.net.RssEgressCodec.NONE, perUserProfiles.tcp(perUser.shadowUsers.get(1), 443).getRssEgressCodec());
        } finally {
            RssClient.closeUpstreamsQuietly(snapshot);
        }
    }

    @Test
    public void dnsAopInterceptsShadowAndSocksTcpUdpAcrossRulesMixedUpstreamsAndServerGuard() throws Exception {
        int[] ports = freeTcpUdpPorts(13);
        int outPort = ports[0], rpcPort = ports[1], stdPort = ports[2], inPort = ports[3], dnsPort = ports[4];
        int nsRegPort = ports[5], nsSyncPort = ports[6];
        int ssProxyPort = ports[7], ssBlockPort = ports[8], ssStdPort = ports[9], ssDirectPort = ports[10];

        SocksUser outUser = new SocksUser("rpcUsr");
        outUser.setPassword("rpcPwd");
        org.rx.net.socks.Authenticator outAuth = (u, p) -> "rpcUsr".equals(u) && "rpcPwd".equals(p) ? outUser : SocksUser.ANONYMOUS;
        SocksConfig outConf = new SocksConfig(outPort);
        RssServer.configureOutboundConfig(outConf, false);
        outConf.setWhiteListEnabled(false);
        outConf.setAdaptiveTcp(false);
        outConf.setRequiredTcpCipherPorts(new int[0]);
        outConf.setRequiredUdpCipherPorts(new int[0]);
        SocksProxyServer outSvr = new SocksProxyServer(outConf, outAuth);
        RssServer.enforceServerDnsPolicy(outSvr);

        SocksConfig stdConf = new SocksConfig(stdPort);
        stdConf.setWhiteListEnabled(false);
        SocksProxyServer stdSocks = new SocksProxyServer(stdConf);
        java.util.Set<Integer> stdSawPorts = java.util.concurrent.ConcurrentHashMap.newKeySet();
        stdSocks.onTcpRoute.replace((s, e) -> {
            stdSawPorts.add(e.getFirstDestination().getPort());
            e.setUpstream(new Upstream(e.getFirstDestination()));
        });
        stdSocks.onUdpRoute.replace((s, e) -> {
            stdSawPorts.add(e.getFirstDestination().getPort());
            e.setUpstream(new Upstream(e.getFirstDestination()));
        });

        AtomicInteger rpcResolveCalls = new AtomicInteger();
        AtomicInteger rpcRecordCalls = new AtomicInteger();
        RssRpcApp realRpc = new RssRpcApp(outSvr);
        SocksRpcContract dnsRpc = new SocksRpcContract() {
            @Override public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
                return realRpc.fakeEndpoint(fakeHost, realEndpoint, token);
            }
            @Override public void addWhiteList(InetAddress endpoint, String token) {
                realRpc.addWhiteList(endpoint, token);
            }
            @Override public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                rpcResolveCalls.incrementAndGet();
                try {
                    if ("secret.example.invalid".equals(host) || "proxy.example.invalid".equals(host)) {
                        return Arrays.asList(InetAddress.getByName("198.51.100.77"), InetAddress.getByName("2001:db8::77"));
                    }
                    return Collections.emptyList();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            @Override public byte[] queryDnsRecord(String domain, int type, boolean tcp, String token) {
                SocksRpcContract.requireValidRpcToken(token);
                rpcRecordCalls.incrementAndGet();
                if ("txt.example.invalid".equals(domain) && type == io.netty.handler.codec.dns.DnsRecordType.TXT.intValue()) {
                    return buildTxtDnsReply(0x1111, domain, "v=rss-rpc-ok");
                }
                return null;
            }
        };
        RpcServerConfig rpcServerCfg = new RpcServerConfig(new TcpServerConfig(rpcPort));
        rpcServerCfg.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcServerCfg.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcServerCfg.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        AutoCloseable rpcServer = Remoting.register(dnsRpc, rpcServerCfg);

        RssClientConf previous = RssClient.rssConf;
        RssRuntime runtime = null;
        try {
            RssClientConf conf = buildDnsAopConf(outPort, rpcPort, stdPort, dnsPort, nsRegPort, nsSyncPort,
                    ssProxyPort, ssBlockPort, ssStdPort, ssDirectPort);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            runtime = new RssRuntime(inPort, conf);
            runtime.dnsSvr.addHosts("direct.example.invalid", "192.0.2.99");

            // 1. Direct SOCKS5 TCP 53 & UDP 53 (NO_AUTH local ingress): mixed [std(w=10), rss(w=1)] selects healthy RPC-capable peer.
            byte[] proxyAQuery = encodeDnsQuery(0x3101, "secret.example.invalid", io.netty.handler.codec.dns.DnsRecordType.A);
            byte[] socksTcpReply = sendSocks5TcpDns(inPort, null, null, "192.0.2.1", proxyAQuery);
            assertEquals(0, socksTcpReply[3] & 0x0f, "PROXY A over SOCKS5 TCP must return NOERROR");
            assertEquals("198.51.100.77", org.rx.net.dns.DoHMessageCodec.decodeAddresses(
                    io.netty.buffer.Unpooled.wrappedBuffer(socksTcpReply), 0x3101).get(0).getHostAddress());
            int callsAfterFirst = rpcResolveCalls.get();
            assertEquals(1, callsAfterFirst);

            // Second A query for direct SOCKS5 hits cache (0 extra RPC calls) over SOCKS5 UDP 53.
            byte[] socksUdpReply = sendSocks5UdpDns(inPort, null, null, "192.0.2.2", proxyAQuery);
            assertEquals("198.51.100.77", org.rx.net.dns.DoHMessageCodec.decodeAddresses(
                    io.netty.buffer.Unpooled.wrappedBuffer(socksUdpReply), 0x3101).get(0).getHostAddress());
            assertEquals(callsAfterFirst, rpcResolveCalls.get(), "same-user cached A query must not re-hit RPC");

            // 2. Non-A/AAAA TXT query over BOTH SOCKS5 TCP 53 and SOCKS5 UDP 53 traverses queryDnsRecord RPC & preserves TXID!
            byte[] txtQuery = encodeDnsQuery(0x4242, "txt.example.invalid", io.netty.handler.codec.dns.DnsRecordType.TXT);
            byte[] txtTcpReply = sendSocks5TcpDns(inPort, null, null, "192.0.2.1", txtQuery);
            assertEquals(0x4242, ((txtTcpReply[0] & 0xff) << 8) | (txtTcpReply[1] & 0xff));
            assertTrue(new String(txtTcpReply, StandardCharsets.US_ASCII).contains("v=rss-rpc-ok"));
            byte[] txtUdpReply = sendSocks5UdpDns(inPort, null, null, "192.0.2.1", txtQuery);
            assertEquals(0x4242, ((txtUdpReply[0] & 0xff) << 8) | (txtUdpReply[1] & 0xff));
            assertTrue(new String(txtUdpReply, StandardCharsets.US_ASCII).contains("v=rss-rpc-ok"));
            assertTrue(rpcRecordCalls.get() >= 2);

            // 3. User rule isolation: u-proxy caches secret.example.invalid via SS, then u-block MUST get REFUSED over SS TCP & UDP!
            byte[] ssProxyReply = sendSsTcpDns(ssProxyPort, "aes-128-gcm", "pwd-proxy", "192.0.2.1", proxyAQuery);
            assertEquals("198.51.100.77", org.rx.net.dns.DoHMessageCodec.decodeAddresses(
                    io.netty.buffer.Unpooled.wrappedBuffer(ssProxyReply), 0x3101).get(0).getHostAddress());
            int callsAfterProxy = rpcResolveCalls.get();
            byte[] blockSsTcpReply = sendSsTcpDns(ssBlockPort, "aes-128-gcm", "pwd-block", "192.0.2.1", proxyAQuery);
            assertEquals(5, blockSsTcpReply[3] & 0x0f, "BLOCK over SS TCP must return REFUSED (RCODE=5)");
            byte[] blockSsUdpReply = sendSsUdpDns(ssBlockPort, "aes-128-gcm", "pwd-block", "192.0.2.1", proxyAQuery);
            assertEquals(5, blockSsUdpReply[3] & 0x0f, "BLOCK over SS UDP must return REFUSED (RCODE=5)");
            assertEquals(callsAfterProxy, rpcResolveCalls.get(), "BLOCK must never invoke RPC");

            // 4. Standard-only SOCKS5 user on PROXY DNS returns SERVFAIL (RCODE=2) and never forwards 53 to stdSocks!
            byte[] stdProxyQuery = encodeDnsQuery(0x5101, "proxy.example.invalid", io.netty.handler.codec.dns.DnsRecordType.A);
            byte[] servfailReply = sendSsUdpDns(ssStdPort, "aes-128-gcm", "pwd-std", "192.0.2.1", stdProxyQuery);
            assertEquals(2, servfailReply[3] & 0x0f, "PROXY DNS without RPC must return SERVFAIL (RCODE=2)");

            // 5. DIRECT user resolves local hosts over SS TCP 53.
            byte[] directQuery = encodeDnsQuery(0x6101, "direct.example.invalid", io.netty.handler.codec.dns.DnsRecordType.A);
            byte[] directReply = sendSsTcpDns(ssDirectPort, "aes-128-gcm", "pwd-direct", "192.0.2.3", directQuery);
            assertEquals("192.0.2.99", org.rx.net.dns.DoHMessageCodec.decodeAddresses(
                    io.netty.buffer.Unpooled.wrappedBuffer(directReply), 0x6101).get(0).getHostAddress());

            // 6. Malformed non-DNS payload on TCP 53 closes cleanly without forwarding upstream.
            try (java.net.Socket malformed = openSocks5Tcp(inPort, null, null, "192.0.2.1", 53)) {
                malformed.setSoTimeout(3000);
                malformed.getOutputStream().write(new byte[]{0x00, 0x05, 0x11, 0x22, 0x33, 0x44, 0x55});
                malformed.getOutputStream().flush();
                assertEquals(-1, malformed.getInputStream().read(), "malformed TCP 53 payload must close connection");
            }

            // 7. Hot reload preserves DNS AOP interception!
            RssClientConf conf2 = buildDnsAopConf(outPort, rpcPort, stdPort, dnsPort, nsRegPort, nsSyncPort,
                    ssProxyPort, ssBlockPort, ssStdPort, ssDirectPort);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf2));
            runtime.reload(conf, conf2);
            runtime.dnsSvr.addHosts("direct.example.invalid", "192.0.2.99");
            byte[] afterReload = sendSsTcpDns(ssProxyPort, "aes-128-gcm", "pwd-proxy", "192.0.2.1", stdProxyQuery);
            assertEquals("198.51.100.77", org.rx.net.dns.DoHMessageCodec.decodeAddresses(
                    io.netty.buffer.Unpooled.wrappedBuffer(afterReload), 0x5101).get(0).getHostAddress());

            // 8. Server-side raw-53 guard: outSvr rejects raw SOCKS5 TCP/UDP port 53 while RPC stays allowed.
            assertThrows(Exception.class, () -> {
                try (java.net.Socket s = openSocks5Tcp(outPort, "rpcUsr", "rpcPwd", "127.0.0.1", 53)) {
                    s.setSoTimeout(1500);
                    s.getOutputStream().write(new byte[]{0, 2, 0, 0});
                    s.getOutputStream().flush();
                    int r = s.getInputStream().read();
                    if (r == -1) throw new java.io.EOFException("rejected");
                }
            });
            assertFalse(stdSawPorts.contains(53), "standard third-party SOCKS5 must never receive port 53 traffic: " + stdSawPorts);
        } finally {
            if (runtime != null) runtime.close();
            RssClient.rssConf = previous;
            rpcServer.close();
            stdSocks.close();
            outSvr.close();
        }
    }

    @Test
    public void nestedSocksCarrierWithPerRouteZlibStoredAndPlainNoneCoexist() throws Exception {
        int[] ports = freeTcpUdpPorts(12);
        int outStoredPort = ports[0], outStoredRpc = ports[1];
        int outNonePort = ports[2], outNoneRpc = ports[3];
        int outerPort = ports[4], inPort = ports[5], dnsPort = ports[6], nsReg = ports[7], nsSync = ports[8];
        int ssStoredPort = ports[9], ssNonePort = ports[10];
        int hopA = freeUdpPortRange(6), hopB = freeUdpPortRange(6);
        if (hopB == hopA) hopB = freeUdpPortRange(12) + 6;

        SocksUser usr = new SocksUser("egressU");
        usr.setPassword("egressP");
        org.rx.net.socks.Authenticator auth = (u, p) -> "egressU".equals(u) && "egressP".equals(p) ? usr : SocksUser.ANONYMOUS;

        SocksConfig storedCfg = new SocksConfig(outStoredPort);
        RssServer.configureOutboundConfig(storedCfg, false);
        storedCfg.setWhiteListEnabled(false);
        storedCfg.setAdaptiveTcp(true);
        storedCfg.setUdpHop(true);
        storedCfg.setDailyPortRange(hopA + "-" + (hopA + 5));
        storedCfg.setRequiredTcpCipherPorts(new int[0]);
        storedCfg.setRequiredUdpCipherPorts(new int[0]);
        storedCfg.setRssEgressCodec(org.rx.net.RssEgressCodec.ZLIB_STORED);
        SocksProxyServer outStored = new SocksProxyServer(storedCfg, auth);

        SocksConfig noneCfg = new SocksConfig(outNonePort);
        RssServer.configureOutboundConfig(noneCfg, false);
        noneCfg.setWhiteListEnabled(false);
        noneCfg.setAdaptiveTcp(true);
        noneCfg.setUdpHop(true);
        noneCfg.setDailyPortRange(hopB + "-" + (hopB + 5));
        noneCfg.setRequiredTcpCipherPorts(new int[0]);
        noneCfg.setRequiredUdpCipherPorts(new int[0]);
        noneCfg.setRssEgressCodec(org.rx.net.RssEgressCodec.NONE);
        SocksProxyServer outNone = new SocksProxyServer(noneCfg, auth);

        RpcServerConfig rpcCfgA = new RpcServerConfig(new TcpServerConfig(outStoredRpc));
        rpcCfgA.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcCfgA.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcCfgA.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        AutoCloseable rpcA = Remoting.register(new RssRpcApp(outStored), rpcCfgA);

        RpcServerConfig rpcCfgB = new RpcServerConfig(new TcpServerConfig(outNoneRpc));
        rpcCfgB.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcCfgB.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcCfgB.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        AutoCloseable rpcB = Remoting.register(new RssRpcApp(outNone), rpcCfgB);

        SocksConfig outerCfg = new SocksConfig(outerPort);
        outerCfg.setWhiteListEnabled(false);
        SocksProxyServer outerSocks = new SocksProxyServer(outerCfg);

        // Transparent TCP tap between outerSocks and outStored to capture first 4 wire bytes (RSZ\x01).
        java.util.concurrent.CopyOnWriteArrayList<Integer> observedPrefaces = new java.util.concurrent.CopyOnWriteArrayList<>();
        ServerSocket tcpTap = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        ServerSocket tcpEcho = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        java.net.DatagramSocket udpEcho = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

        startLoopThread(running, () -> {
            java.net.Socket client = tcpTap.accept();
            Thread bridge = new Thread(() -> {
                try (java.net.Socket inSock = client;
                     java.net.Socket outSock = new java.net.Socket("127.0.0.1", outStoredPort)) {
                    Thread back = new Thread(() -> {
                        try {
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = outSock.getInputStream().read(b)) != -1) {
                                inSock.getOutputStream().write(b, 0, n);
                                inSock.getOutputStream().flush();
                            }
                        } catch (Exception ignored) {
                        }
                    });
                    back.setDaemon(true);
                    back.start();
                    byte[] hdr = new byte[4];
                    int read = 0;
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = inSock.getInputStream().read(b)) != -1) {
                        for (int i = 0; i < n && read < 4; i++) hdr[read++] = b[i];
                        if (read == 4) {
                            int word = ((hdr[0] & 0xff) << 24) | ((hdr[1] & 0xff) << 16) | ((hdr[2] & 0xff) << 8) | (hdr[3] & 0xff);
                            observedPrefaces.add(word);
                            read = 5;
                        }
                        outSock.getOutputStream().write(b, 0, n);
                        outSock.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                }
            });
            bridge.setDaemon(true);
            bridge.start();
        });
        startLoopThread(running, () -> {
            java.net.Socket s = tcpEcho.accept();
            Thread w = new Thread(() -> {
                try (java.net.Socket sock = s) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = sock.getInputStream().read(b)) != -1) {
                        sock.getOutputStream().write(b, 0, n);
                        sock.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                }
            });
            w.setDaemon(true);
            w.start();
        });
        startLoopThread(running, () -> {
            byte[] b = new byte[2048];
            java.net.DatagramPacket p = new java.net.DatagramPacket(b, b.length);
            udpEcho.receive(p);
            udpEcho.send(new java.net.DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
        });

        RssClientConf previous = RssClient.rssConf;
        RssRuntime runtime = null;
        try {
            RssClientConf conf = new RssClientConf();
            conf.socksPwd = "inner-socks-pwd";
            conf.shadowDnsPort = dnsPort;
            conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};
            conf.rpcAutoWhiteListSeconds = 0;
            conf.upstreamHealthCheckSeconds = 60;
            conf.udpLeasePoolEnabled = false;
            conf.tcpCipherPorts = new int[0];
            conf.udpCipherPorts = new int[0];
            conf.defaultRoute = new UserRule();
            conf.defaultRoute.setEnabled(Boolean.TRUE);
            conf.defaultRoute.setRules(Collections.singletonList("default proxy"));
            conf.nameserver = new NameserverConfig();
            conf.nameserver.setRegisterPort(nsReg);
            conf.nameserver.setSyncPort(nsSync);

            RssClientConf.SocksServer storedRoute = new RssClientConf.SocksServer("stored-nested", 1,
                    AuthenticEndpoint.valueOf("127.0.0.1:" + outerPort));
            storedRoute.type = RssClientConf.UpstreamType.SOCKS5;
            storedRoute.rssEndpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", tcpTap.getLocalPort()), "egressU", "egressP");
            storedRoute.rpcEndpoint = new InetSocketAddress("127.0.0.1", outStoredRpc);
            storedRoute.egress = new RssClientConf.Egress();
            storedRoute.egress.tcpCodec = "zlib-stored";

            RssClientConf.SocksServer noneRoute = new RssClientConf.SocksServer("none-direct", 1,
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", outNonePort), "egressU", "egressP"));
            noneRoute.rpcEndpoint = new InetSocketAddress("127.0.0.1", outNoneRpc);
            noneRoute.egress = new RssClientConf.Egress();
            noneRoute.egress.tcpCodec = "none";
            conf.socksServers = Arrays.asList(storedRoute, noneRoute);

            ShadowUser uStored = new ShadowUser();
            uStored.setUsername("u-stored");
            uStored.setSocksUser("u-stored");
            uStored.setSsPort(ssStoredPort);
            uStored.setSsPwd("pwd-stored");
            uStored.transport = new RssTransport();
            uStored.transport.profile = "daily";
            uStored.setSocksServers(Collections.singletonList("stored-nested"));

            ShadowUser uNone = new ShadowUser();
            uNone.setUsername("u-none");
            uNone.setSocksUser("u-none");
            uNone.setSsPort(ssNonePort);
            uNone.setSsPwd("pwd-none");
            uNone.transport = new RssTransport();
            uNone.transport.profile = "daily";
            uNone.setSocksServers(Collections.singletonList("none-direct"));

            conf.shadowUsers = Arrays.asList(uStored, uNone);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            runtime = new RssRuntime(inPort, conf);

            // Large repetitive payload through nested SOCKS5 + ZLIB_STORED + Adaptive TCP + business zlib 3.
            byte[] large = new byte[12 * 1024];
            Arrays.fill(large, (byte) 'Z');
            org.rx.net.socks.encryption.ICrypto cStored = org.rx.net.socks.encryption.ICrypto.get(
                    CipherKind.AES_256_GCM.getCipherName(), "pwd-stored");
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", ssStoredPort)) {
                s.setSoTimeout(6000);
                ByteBuf init = io.netty.buffer.Unpooled.buffer();
                org.rx.net.socks.UdpManager.encode(init, "127.0.0.1", tcpEcho.getLocalPort());
                init.writeBytes(large);
                s.getOutputStream().write(drainBuf(cStored.encrypt(init)));
                s.getOutputStream().flush();
                assertEquals(new String(large, StandardCharsets.US_ASCII), readDecryptedString(s, cStored, large.length));
            } finally {
                if (cStored instanceof AutoCloseable) ((AutoCloseable) cStored).close();
            }
            assertSsUdpEcho(ssStoredPort, CipherKind.AES_256_GCM.getCipherName(), "pwd-stored", udpEcho.getLocalPort(), true);

            // Coexisting NONE route on the same RssClient succeeds cleanly!
            assertSsTcpEcho(ssNonePort, CipherKind.AES_256_GCM.getCipherName(), "pwd-none", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssNonePort, CipherKind.AES_256_GCM.getCipherName(), "pwd-none", udpEcho.getLocalPort(), true);

            assertTrue(observedPrefaces.contains(0x52535a01),
                    "nested SOCKS5 route with egress=zlib-stored must emit RSZ\\x01 preface on outer TCP: " + observedPrefaces);
        } finally {
            running.set(false);
            if (runtime != null) runtime.close();
            RssClient.rssConf = previous;
            tcpTap.close();
            tcpEcho.close();
            udpEcho.close();
            outerSocks.close();
            rpcA.close();
            rpcB.close();
            outStored.close();
            outNone.close();
        }
    }

    @Test
    public void fourProfilesAcrossThreeUpstreamsMatrixE2E() throws Exception {
        int[] ports = freeTcpUdpPorts(13);
        int outPort = ports[0], fixedUdpPort = ports[1], rpcPort = ports[2];
        int outerPort = ports[3], stdPort = ports[4], inPort = ports[5], dnsPort = ports[6];
        int nsReg = ports[7], nsSync = ports[8];
        int ssGameFixed = ports[9], ssWeakNested = ports[10], ssPlainStd = ports[11], ssGameRss = ports[12];
        int hopBase = freeUdpPortRange(8);

        SocksUser usr = new SocksUser("matrixU");
        usr.setPassword("matrixP");
        org.rx.net.socks.Authenticator auth = (u, p) -> "matrixU".equals(u) && "matrixP".equals(p) ? usr : SocksUser.ANONYMOUS;

        SocksConfig outConf = new SocksConfig(outPort);
        RssServer.configureOutboundConfig(outConf, false);
        outConf.setWhiteListEnabled(false);
        outConf.setAdaptiveTcp(true);
        outConf.setUdpHop(true);
        outConf.setFixedUdpPort(fixedUdpPort);
        outConf.setDailyPortRange(hopBase + "-" + (hopBase + 7));
        outConf.setRequiredTcpCipherPorts(new int[0]);
        outConf.setRequiredUdpCipherPorts(new int[0]);
        SocksProxyServer outSvr = new SocksProxyServer(outConf, auth);

        RpcServerConfig rpcCfg = new RpcServerConfig(new TcpServerConfig(rpcPort));
        rpcCfg.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcCfg.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcCfg.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        AutoCloseable rpcServer = Remoting.register(new RssRpcApp(outSvr), rpcCfg);

        SocksConfig outerCfg = new SocksConfig(outerPort);
        outerCfg.setWhiteListEnabled(false);
        SocksProxyServer outerSocks = new SocksProxyServer(outerCfg);
        java.util.Set<Integer> outerUdpPorts = java.util.concurrent.ConcurrentHashMap.newKeySet();
        outerSocks.onUdpRoute.replace((s, e) -> {
            outerUdpPorts.add(e.getFirstDestination().getPort());
            e.setUpstream(new Upstream(e.getFirstDestination()));
        });

        SocksConfig stdCfg = new SocksConfig(stdPort);
        stdCfg.setWhiteListEnabled(false);
        SocksProxyServer stdSocks = new SocksProxyServer(stdCfg);

        ServerSocket tcpEcho = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        ServerSocket protectedEcho = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        java.net.DatagramSocket udpEcho = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawAdaptCli = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawAdaptSvr = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawUdpCli = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket rawUdpSvr = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger adaptC2S = new AtomicInteger(), adaptS2C = new AtomicInteger();
        AtomicInteger ordC2S = new AtomicInteger(), ordS2C = new AtomicInteger();
        AtomicInteger protectedHits = new AtomicInteger();

        startLoopThread(running, () -> {
            java.net.Socket s = tcpEcho.accept();
            Thread w = new Thread(() -> {
                try (java.net.Socket sock = s) {
                    byte[] b = new byte[2048];
                    int n;
                    while ((n = sock.getInputStream().read(b)) != -1) {
                        sock.getOutputStream().write(b, 0, n);
                        sock.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                }
            });
            w.setDaemon(true);
            w.start();
        });
        startLoopThread(running, () -> {
            try (java.net.Socket s = protectedEcho.accept()) {
                protectedHits.incrementAndGet();
            }
        });
        startLoopThread(running, () -> {
            byte[] b = new byte[2048];
            java.net.DatagramPacket p = new java.net.DatagramPacket(b, b.length);
            udpEcho.receive(p);
            udpEcho.send(new java.net.DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
        });
        int adaptiveServerUdpPort = getAdaptiveUdpPortNumber(outSvr);
        startUdpForwarder(running, rawAdaptCli, rawAdaptSvr,
                new InetSocketAddress("127.0.0.1", adaptiveServerUdpPort), adaptC2S, adaptS2C);
        startUdpForwarder(running, rawUdpCli, rawUdpSvr,
                new InetSocketAddress("127.0.0.1", fixedUdpPort), ordC2S, ordS2C);

        RssClientConf previous = RssClient.rssConf;
        RssRuntime runtime = null;
        try {
            RssClientConf conf = new RssClientConf();
            conf.socksPwd = "inner-socks-pwd";
            conf.rpcPort = rpcPort;
            conf.shadowDnsPort = dnsPort;
            conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};
            conf.rpcAutoWhiteListSeconds = 0;
            conf.upstreamHealthCheckSeconds = 60;
            conf.udpLeasePoolEnabled = false;
            // Mark protectedEcho's port as requiring TCP AES so standard third-party SOCKS5 MUST refuse it!
            conf.tcpCipherPorts = new int[]{protectedEcho.getLocalPort()};
            conf.udpCipherPorts = new int[0];
            conf.defaultRoute = new UserRule();
            conf.defaultRoute.setEnabled(Boolean.TRUE);
            conf.defaultRoute.setRules(Collections.singletonList("default proxy"));
            conf.nameserver = new NameserverConfig();
            conf.nameserver.setRegisterPort(nsReg);
            conf.nameserver.setSyncPort(nsSync);

            AuthenticEndpoint peerEp = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", outPort), "matrixU", "matrixP");
            RssClientConf.SocksServer rssDirect = new RssClientConf.SocksServer("rss-direct", 1, peerEp);

            RssClientConf.SocksServer fixedTun = new RssClientConf.SocksServer("fixed-tun", 1, peerEp);
            fixedTun.type = RssClientConf.UpstreamType.UDPCLIENT;
            fixedTun.tcpClient = peerEp;
            fixedTun.setAdaptiveUdpClient((InetSocketAddress) rawAdaptCli.getLocalSocketAddress());
            fixedTun.setUdpClient((InetSocketAddress) rawUdpCli.getLocalSocketAddress());

            RssClientConf.SocksServer nestedSocks = new RssClientConf.SocksServer("nested-socks", 1,
                    AuthenticEndpoint.valueOf("127.0.0.1:" + outerPort));
            nestedSocks.type = RssClientConf.UpstreamType.SOCKS5;
            nestedSocks.rssEndpoint = peerEp;

            RssClientConf.SocksServer stdRoute = new RssClientConf.SocksServer("std-socks", 1,
                    AuthenticEndpoint.valueOf("127.0.0.1:" + stdPort));
            stdRoute.type = RssClientConf.UpstreamType.SOCKS5;

            conf.socksServers = Arrays.asList(rssDirect, fixedTun, nestedSocks, stdRoute);

            ShadowUser uGameFixed = new ShadowUser();
            uGameFixed.setUsername("u-game-fixed");
            uGameFixed.setSocksUser("u-game-fixed");
            uGameFixed.setSsPort(ssGameFixed);
            uGameFixed.setSsPwd("pwd-gf");
            uGameFixed.transport = new RssTransport();
            uGameFixed.transport.profile = "game";
            uGameFixed.transport.udpHop = Boolean.FALSE;
            uGameFixed.transport.udpMtu = 1260;
            uGameFixed.setSocksServers(Collections.singletonList("fixed-tun"));

            ShadowUser uWeakNested = new ShadowUser();
            uWeakNested.setUsername("u-weak-nested");
            uWeakNested.setSocksUser("u-weak-nested");
            uWeakNested.setSsPort(ssWeakNested);
            uWeakNested.setSsPwd("pwd-wn");
            uWeakNested.transport = new RssTransport();
            uWeakNested.transport.profile = "weak";
            uWeakNested.setSocksServers(Collections.singletonList("nested-socks"));

            ShadowUser uPlainStd = new ShadowUser();
            uPlainStd.setUsername("u-plain-std");
            uPlainStd.setSocksUser("u-plain-std");
            uPlainStd.setSsPort(ssPlainStd);
            uPlainStd.setSsPwd("pwd-ps");
            uPlainStd.setSocksServers(Collections.singletonList("std-socks"));

            ShadowUser uGameRss = new ShadowUser();
            uGameRss.setUsername("u-game-rss");
            uGameRss.setSocksUser("u-game-rss");
            uGameRss.setSsPort(ssGameRss);
            uGameRss.setSsPwd("pwd-gr");
            uGameRss.transport = new RssTransport();
            uGameRss.transport.profile = "game";
            uGameRss.setSocksServers(Collections.singletonList("rss-direct"));

            conf.shadowUsers = Arrays.asList(uGameFixed, uWeakNested, uPlainStd, uGameRss);
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            runtime = new RssRuntime(inPort, conf);

            // 1. game over udpclient (no-hop)
            assertSsTcpEcho(ssGameFixed, CipherKind.AES_256_GCM.getCipherName(), "pwd-gf", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssGameFixed, CipherKind.AES_256_GCM.getCipherName(), "pwd-gf", udpEcho.getLocalPort(), true);
            assertTrue(ordC2S.get() > 0 && ordS2C.get() > 0, "game over udpclient must traverse fixed ordinary UDP mapping");

            // 2. game over rss (hop)
            assertSsTcpEcho(ssGameRss, CipherKind.AES_256_GCM.getCipherName(), "pwd-gr", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssGameRss, CipherKind.AES_256_GCM.getCipherName(), "pwd-gr", udpEcho.getLocalPort(), true);

            // 3. weak over nested socks5 carrier
            assertSsTcpEcho(ssWeakNested, CipherKind.AES_256_GCM.getCipherName(), "pwd-wn", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssWeakNested, CipherKind.AES_256_GCM.getCipherName(), "pwd-wn", udpEcho.getLocalPort(), true);
            assertFalse(outerUdpPorts.isEmpty(), "weak over nested SOCKS5 must carry UDP over outer carrier");

            // 4. plain over standard third-party socks5: default TCP & UDP succeed, protected port is REJECTED (no plaintext downgrade)!
            assertSsTcpEcho(ssPlainStd, CipherKind.AES_256_GCM.getCipherName(), "pwd-ps", tcpEcho.getLocalPort(), true);
            assertSsUdpEcho(ssPlainStd, CipherKind.AES_256_GCM.getCipherName(), "pwd-ps", udpEcho.getLocalPort(), true);
            assertSsTcpEcho(ssPlainStd, CipherKind.AES_256_GCM.getCipherName(), "pwd-ps", protectedEcho.getLocalPort(), false);
            assertEquals(0, protectedHits.get(), "protected port must never downgrade to plaintext on standard SOCKS5");
        } finally {
            running.set(false);
            if (runtime != null) runtime.close();
            RssClient.rssConf = previous;
            tcpEcho.close();
            protectedEcho.close();
            udpEcho.close();
            rawAdaptCli.close();
            rawAdaptSvr.close();
            rawUdpCli.close();
            rawUdpSvr.close();
            outerSocks.close();
            stdSocks.close();
            rpcServer.close();
            outSvr.close();
        }
    }

    private static RssClientConf buildDnsAopConf(int outPort, int rpcPort, int stdPort, int dnsPort,
                                                 int nsRegPort, int nsSyncPort,
                                                 int ssProxyPort, int ssBlockPort, int ssStdPort, int ssDirectPort) {
        RssClientConf conf = new RssClientConf();
        conf.socksBindPort = true;
        conf.socksPwd = "inner-socks-pwd";
        conf.rpcPort = rpcPort;
        conf.shadowDnsPort = dnsPort;
        conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};
        conf.rpcAutoWhiteListSeconds = 0;
        conf.upstreamHealthCheckSeconds = 60;
        conf.udpLeasePoolEnabled = false;
        conf.tcpCipherPorts = new int[0];
        conf.udpCipherPorts = new int[0];
        conf.defaultRoute = new UserRule();
        conf.defaultRoute.setEnabled(Boolean.TRUE);
        conf.defaultRoute.setRules(Collections.singletonList("default proxy"));
        conf.nameserver = new NameserverConfig();
        conf.nameserver.setRegisterPort(nsRegPort);
        conf.nameserver.setSyncPort(nsSyncPort);

        RssClientConf.SocksServer std = new RssClientConf.SocksServer("std", 10,
                AuthenticEndpoint.valueOf("127.0.0.1:" + stdPort));
        std.type = RssClientConf.UpstreamType.SOCKS5;
        RssClientConf.SocksServer rss = new RssClientConf.SocksServer("rss", 1,
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", outPort), "rpcUsr", "rpcPwd"));
        conf.socksServers = Arrays.asList(std, rss);

        ShadowUser uProxy = new ShadowUser();
        uProxy.setUsername("u-proxy");
        uProxy.setSocksUser("u-proxy");
        uProxy.setSsPort(ssProxyPort);
        uProxy.setSsPwd("pwd-proxy");
        uProxy.setSsCipher("aes-128-gcm");
        uProxy.setSocksServers(Arrays.asList("std", "rss"));

        ShadowUser uBlock = new ShadowUser();
        uBlock.setUsername("u-block");
        uBlock.setSocksUser("u-block");
        uBlock.setSsPort(ssBlockPort);
        uBlock.setSsPwd("pwd-block");
        uBlock.setSsCipher("aes-128-gcm");
        uBlock.setSocksServers(Arrays.asList("std", "rss"));
        UserRule blockRule = new UserRule();
        blockRule.setEnabled(Boolean.TRUE);
        blockRule.setRules(Arrays.asList("domain:secret.example.invalid block", "default proxy"));
        uBlock.setRoute(blockRule);

        ShadowUser uStd = new ShadowUser();
        uStd.setUsername("u-std");
        uStd.setSocksUser("u-std");
        uStd.setSsPort(ssStdPort);
        uStd.setSsPwd("pwd-std");
        uStd.setSsCipher("aes-128-gcm");
        uStd.setSocksServers(Collections.singletonList("std"));

        ShadowUser uDirect = new ShadowUser();
        uDirect.setUsername("u-direct");
        uDirect.setSocksUser("u-direct");
        uDirect.setSsPort(ssDirectPort);
        uDirect.setSsPwd("pwd-direct");
        uDirect.setSsCipher("aes-128-gcm");
        UserRule directRule = new UserRule();
        directRule.setEnabled(Boolean.TRUE);
        directRule.setRules(Collections.singletonList("default direct"));
        uDirect.setRoute(directRule);

        conf.shadowUsers = Arrays.asList(uProxy, uBlock, uStd, uDirect);
        return conf;
    }

    private static byte[] encodeDnsQuery(int id, String domain, io.netty.handler.codec.dns.DnsRecordType type) {
        ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        org.rx.net.dns.DoHMessageCodec.encodeQuery(buf, id, domain, type);
        return drainBuf(buf);
    }

    private static byte[] buildTxtDnsReply(int id, String domain, String text) {
        byte[] txtBytes = text.getBytes(StandardCharsets.US_ASCII);
        io.netty.handler.codec.dns.DefaultDnsResponse resp = new io.netty.handler.codec.dns.DefaultDnsResponse(
                id, io.netty.handler.codec.dns.DnsOpCode.QUERY, io.netty.handler.codec.dns.DnsResponseCode.NOERROR);
        resp.setRecursionAvailable(true);
        resp.addRecord(io.netty.handler.codec.dns.DnsSection.QUESTION,
                new io.netty.handler.codec.dns.DefaultDnsQuestion(domain, io.netty.handler.codec.dns.DnsRecordType.TXT));
        ByteBuf rdata = io.netty.buffer.Unpooled.buffer(1 + txtBytes.length).writeByte(txtBytes.length).writeBytes(txtBytes);
        resp.addRecord(io.netty.handler.codec.dns.DnsSection.ANSWER,
                new io.netty.handler.codec.dns.DefaultDnsRawRecord(domain, io.netty.handler.codec.dns.DnsRecordType.TXT, 60, rdata));
        ByteBuf out = io.netty.buffer.Unpooled.buffer(256);
        try {
            org.rx.net.dns.DoHMessageCodec.encodeResponse(out, resp);
            return drainBuf(out.retain());
        } finally {
            out.release();
            resp.release();
        }
    }

    private static java.net.Socket openSocks5Tcp(int proxyPort, String user, String pass, String dstIp, int dstPort) throws Exception {
        java.net.Socket s = new java.net.Socket("127.0.0.1", proxyPort);
        s.setSoTimeout(5000);
        try {
            java.io.OutputStream out = s.getOutputStream();
            java.io.InputStream in = s.getInputStream();
            byte wantMethod = user == null ? (byte) 0x00 : (byte) 0x02;
            out.write(new byte[]{0x05, 0x01, wantMethod});
            out.flush();
            byte[] method = readExactBytes(in, 2);
            if (method[0] != 0x05 || method[1] != wantMethod) throw new java.io.IOException("auth method rejected: " + (method[1] & 0xff));
            if (user != null) {
                byte[] ub = user.getBytes(StandardCharsets.UTF_8), pb = pass.getBytes(StandardCharsets.UTF_8);
                ByteBuf authReq = io.netty.buffer.Unpooled.buffer(3 + ub.length + pb.length)
                        .writeByte(0x01).writeByte(ub.length).writeBytes(ub).writeByte(pb.length).writeBytes(pb);
                out.write(drainBuf(authReq));
                out.flush();
                byte[] authResp = readExactBytes(in, 2);
                if (authResp[1] != 0x00) throw new java.io.IOException("socks5 auth failed");
            }
            byte[] ip = InetAddress.getByName(dstIp).getAddress();
            out.write(new byte[]{0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3],
                    (byte) (dstPort >>> 8), (byte) dstPort});
            out.flush();
            byte[] connResp = readExactBytes(in, 10);
            if (connResp[1] != 0x00) throw new java.io.IOException("socks5 connect failed rep=" + (connResp[1] & 0xff));
            return s;
        } catch (Exception e) {
            s.close();
            throw e;
        }
    }

    private static byte[] sendSocks5TcpDns(int proxyPort, String user, String pass, String dstIp, byte[] query) throws Exception {
        try (java.net.Socket s = openSocks5Tcp(proxyPort, user, pass, dstIp, 53)) {
            java.io.DataOutputStream dos = new java.io.DataOutputStream(s.getOutputStream());
            dos.writeShort(query.length);
            dos.write(query);
            dos.flush();
            java.io.DataInputStream dis = new java.io.DataInputStream(s.getInputStream());
            int len = dis.readUnsignedShort();
            byte[] resp = new byte[len];
            dis.readFully(resp);
            return resp;
        }
    }

    private static byte[] sendSocks5UdpDns(int proxyPort, String user, String pass, String dstIp, byte[] query) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", proxyPort);
             java.net.DatagramSocket udp = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            s.setSoTimeout(5000);
            udp.setSoTimeout(5000);
            java.io.OutputStream out = s.getOutputStream();
            java.io.InputStream in = s.getInputStream();
            byte wantMethod = user == null ? (byte) 0x00 : (byte) 0x02;
            out.write(new byte[]{0x05, 0x01, wantMethod});
            out.flush();
            assertEquals(wantMethod, readExactBytes(in, 2)[1]);
            if (user != null) {
                byte[] ub = user.getBytes(StandardCharsets.UTF_8), pb = pass.getBytes(StandardCharsets.UTF_8);
                ByteBuf authReq = io.netty.buffer.Unpooled.buffer(3 + ub.length + pb.length)
                        .writeByte(0x01).writeByte(ub.length).writeBytes(ub).writeByte(pb.length).writeBytes(pb);
                out.write(drainBuf(authReq));
                out.flush();
                assertEquals(0x00, readExactBytes(in, 2)[1]);
            }
            out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            byte[] rep = readExactBytes(in, 4);
            assertEquals(0x00, rep[1]);
            int addrLen = rep[3] == 0x01 ? 4 : (rep[3] == 0x04 ? 16 : (in.read() & 0xff));
            byte[] rest = readExactBytes(in, addrLen + 2);
            int relayPort = ((rest[addrLen] & 0xff) << 8) | (rest[addrLen + 1] & 0xff);
            ByteBuf req = org.rx.net.socks.UdpManager.socks5Encode(
                    io.netty.buffer.Unpooled.wrappedBuffer(query), new InetSocketAddress(dstIp, 53));
            byte[] wire = drainBuf(req);
            udp.send(new java.net.DatagramPacket(wire, wire.length, InetAddress.getLoopbackAddress(), relayPort));
            java.net.DatagramPacket pkt = new java.net.DatagramPacket(new byte[2048], 2048);
            udp.receive(pkt);
            ByteBuf dec = io.netty.buffer.Unpooled.wrappedBuffer(pkt.getData(), 0, pkt.getLength());
            InetSocketAddress from = org.rx.net.socks.UdpManager.socks5Decode(dec);
            assertEquals(dstIp, from.getHostString());
            assertEquals(53, from.getPort());
            byte[] reply = new byte[dec.readableBytes()];
            dec.readBytes(reply);
            return reply;
        }
    }

    private static byte[] sendSsUdpDns(int ssPort, String method, String pwd, String dstIp, byte[] query) throws Exception {
        org.rx.net.socks.encryption.ICrypto udp = org.rx.net.socks.encryption.ICrypto.get(method, pwd, true);
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(5000);
            ByteBuf payload = io.netty.buffer.Unpooled.buffer();
            org.rx.net.socks.UdpManager.encode(payload, dstIp, 53);
            byte[] wire = drainBuf(udp.encrypt(payload.writeBytes(query)));
            socket.send(new java.net.DatagramPacket(wire, wire.length, InetAddress.getLoopbackAddress(), ssPort));
            java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[2048], 2048);
            socket.receive(packet);
            ByteBuf reply = udp.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength()));
            try {
                InetSocketAddress from = org.rx.net.socks.UdpManager.decode(reply);
                assertEquals(dstIp, from.getHostString());
                assertEquals(53, from.getPort());
                byte[] out = new byte[reply.readableBytes()];
                reply.readBytes(out);
                return out;
            } finally {
                reply.release();
            }
        } finally {
            if (udp instanceof AutoCloseable) ((AutoCloseable) udp).close();
        }
    }

    private static byte[] sendSsTcpDns(int ssPort, String method, String pwd, String dstIp, byte[] query) throws Exception {
        org.rx.net.socks.encryption.ICrypto tcp = org.rx.net.socks.encryption.ICrypto.get(method, pwd);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", ssPort)) {
            socket.setSoTimeout(5000);
            ByteBuf payload = io.netty.buffer.Unpooled.buffer();
            org.rx.net.socks.UdpManager.encode(payload, dstIp, 53);
            socket.getOutputStream().write(drainBuf(tcp.encrypt(payload.writeShort(query.length).writeBytes(query))));
            socket.getOutputStream().flush();
            ByteBuf reply = io.netty.buffer.Unpooled.buffer();
            try {
                byte[] input = new byte[1024];
                while (reply.readableBytes() < 2 || reply.readableBytes() < 2 + reply.getUnsignedShort(0)) {
                    int n = socket.getInputStream().read(input);
                    assertTrue(n > 0);
                    reply.writeBytes(drainBuf(tcp.decrypt(io.netty.buffer.Unpooled.wrappedBuffer(input, 0, n))));
                }
                int len = reply.readUnsignedShort();
                byte[] out = new byte[len];
                reply.readBytes(out);
                return out;
            } finally {
                reply.release();
            }
        } finally {
            if (tcp instanceof AutoCloseable) ((AutoCloseable) tcp).close();
        }
    }

    private static byte[] readExactBytes(java.io.InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new java.io.EOFException("unexpected EOF at " + off + "/" + len);
            off += n;
        }
        return buf;
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
                InetSocketAddress dstEp = org.rx.net.socks.UdpManager.socks5Decode(buf);
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
        createSocksSvr();
        System.in.read();
    }

    void createSocksSvr() {
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
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        Upstream shadowDnsUpstream = new Upstream(shadowDnsEp);
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            InetSocketAddress dstEp = e.getFirstDestination();
            if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                return;
            }
            if (Sockets.isBypass(bypassHosts, dstEp.getHostString())) {
                e.setUpstream(new Upstream(dstEp));
            }
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            e.setUpstream(new SocksTcpUpstream(dstEp, inConf, socksServers.next()));
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            e.setUpstream(new SocksUdpUpstream(dstEp, inConf, socksServers.next()));
        });

        AuthenticEndpoint inSrvEp = new AuthenticEndpoint(Sockets.newLoopbackEndpoint(inSvrPort));
        ShadowsocksConfig frontConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(ssPort),
                CipherKind.AES_256_GCM.getCipherName(), socks5Pwd);
        ShadowsocksServer frontSvr = new ShadowsocksServer(frontConf);
        frontSvr.onTcpRoute.replace((s, e) -> e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
        frontSvr.onUdpRoute.replace((s, e) -> e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
    }

    private static int[] freeTcpUdpPorts(int count) throws Exception {
        java.util.List<ServerSocket> tcpHeld = new java.util.ArrayList<>(count);
        java.util.List<java.net.DatagramSocket> udpHeld = new java.util.ArrayList<>(count);
        int[] result = new int[count];
        try {
            int found = 0;
            for (int attempt = 0; attempt < count * 32 && found < count; attempt++) {
                int port = java.util.concurrent.ThreadLocalRandom.current().nextInt(10000, 60000);
                ServerSocket tcp = new ServerSocket();
                java.net.DatagramSocket udp = null;
                try {
                    tcp.bind(new InetSocketAddress(port));
                    udp = new java.net.DatagramSocket(null);
                    udp.setReuseAddress(false);
                    udp.bind(new InetSocketAddress(port));
                } catch (java.net.SocketException busy) {
                    tcp.close();
                    if (udp != null) udp.close();
                    continue;
                }
                tcpHeld.add(tcp);
                udpHeld.add(udp);
                result[found++] = port;
            }
            if (found < count) {
                throw new IllegalStateException("Unable to allocate " + count + " free TCP+UDP ports");
            }
            return result;
        } finally {
            for (java.net.DatagramSocket u : udpHeld) {
                u.close();
            }
            for (ServerSocket t : tcpHeld) {
                t.close();
            }
        }
    }

    private static int freePort() throws Exception {
        for (int i = 0; i < 32; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                java.net.DatagramSocket udp = new java.net.DatagramSocket(null);
                try {
                    udp.setReuseAddress(false);
                    udp.bind(new InetSocketAddress(port));
                    return port;
                } catch (java.net.SocketException busy) {
                    // Retry when Windows already has a UDP socket on this port.
                } finally {
                    udp.close();
                }
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
