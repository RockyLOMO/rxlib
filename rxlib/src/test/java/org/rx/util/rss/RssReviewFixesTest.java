package org.rx.util.rss;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import org.junit.jupiter.api.Test;
import org.rx.bean.RandomList;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsResolveInterceptor;
import org.rx.net.dns.DoHMessageCodec;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.support.UpstreamSupport;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RssReviewFixesTest {

    private static int[] freePorts(int count) throws Exception {
        ServerSocket[] sockets = new ServerSocket[count];
        int[] ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    private static RssClientConf baseConf(int[] ports, String userName, String ssPwd, String blockedDomain) throws Exception {
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "test-pwd";
        conf.socksBindPort = false;
        conf.shadowDnsPort = ports[0];
        conf.nameserver.setRegisterPort(ports[1]);
        conf.nameserver.setSyncPort(ports[2]);
        conf.tcpCipherPorts = new int[0];
        conf.udpCipherPorts = new int[0];
        conf.rpcAutoWhiteListSeconds = 0;
        conf.publicIpEndpoints = new String[0];

        RssClientConf.SocksServer std = new RssClientConf.SocksServer();
        std.id = "std-1";
        std.type = RssClientConf.UpstreamType.SOCKS5;
        std.weight = 10;
        std.endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", ports[3]), "u", "p");
        conf.socksServers = Collections.singletonList(std);

        ShadowUser user = new ShadowUser();
        user.setUsername(userName);
        user.setSocksUser("socks-u");
        user.setSsPort(ports[4]);
        user.setSsPwd(ssPwd);
        RssTransport plain = new RssTransport();
        plain.profile = "plain";
        plain.udp = 0;
        plain.udpHop = false;
        user.transport = plain;
        if (blockedDomain != null) {
            UserRule rule = new UserRule();
            rule.setEnabled(Boolean.TRUE);
            rule.setRules(Arrays.asList("domain:" + blockedDomain + " block", "default proxy"));
            user.setRoute(rule);
        }
        conf.shadowUsers = Collections.singletonList(user);
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        return conf;
    }

    @Test
    void reloadPostPublishFailure_RollsBackGenerationAuthShadowAndDns() throws Exception {
        int[] ports = freePorts(10);
        RssClientConf conf1 = baseConf(Arrays.copyOfRange(ports, 0, 5), "user-v1", "pwd-v1", "old-block.example");
        RssClientConf conf2 = baseConf(Arrays.copyOfRange(ports, 5, 10), "user-v2", "pwd-v2", "new-block.example");
        conf2.socksPwd = "test-pwd-v2";
        conf2.dnsTtlMinutes = 9;

        RssRuntime runtime = null;
        try {
            runtime = new RssRuntime(ports[4] + 1000, conf1);
            RssRuntime.RuntimeGeneration genBefore = runtime.currentGeneration();
            RssRuntime.ShadowServerRef shadowBefore = runtime.shadowServers.get("user-v1");
            assertNotNull(genBefore);
            assertNotNull(shadowBefore);
            assertEquals("user-v1", runtime.authenticator.getShadowStore().keySet().iterator().next());

            runtime.reloadPostPublishHook = () -> {
                throw new IllegalStateException("injected post-publish reload failure");
            };

            RssRuntime rt = runtime;
            assertThrows(RuntimeException.class, () -> rt.reload(conf1, conf2));

            assertSame(genBefore, runtime.currentGeneration());
            assertSame(conf1, runtime.activeConf);
            assertSame(conf1, RssClient.rssConf);
            assertSame(genBefore.upstreamSnapshot, runtime.upstreamSnapshot);
            assertSame(genBefore.dnsServer, runtime.dnsSvr);
            assertEquals("test-pwd", runtime.authenticator.getSocksPassword());
            assertTrue(runtime.authenticator.getShadowStore().containsKey("user-v1"));
            assertFalse(runtime.authenticator.getShadowStore().containsKey("user-v2"));
            assertSame(shadowBefore, runtime.shadowServers.get("user-v1"));
            assertNull(runtime.shadowServers.get("user-v2"));
            assertEquals(RouteAction.BLOCK, shadowBefore.routeMatcher.match("old-block.example", 443, null));
            assertEquals(RouteAction.PROXY, shadowBefore.routeMatcher.match("new-block.example", 443, null));
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    @Test
    void compatibleUpstreams_PreFiltersMixedRssAndStandardSocks5ForProtectedPorts() throws Exception {
        SocksRpcContract dummyRpc = (SocksRpcContract) Proxy.newProxyInstance(
                SocksRpcContract.class.getClassLoader(),
                new Class<?>[]{SocksRpcContract.class},
                (proxy, method, args) -> null);

        UpstreamSupport stdSocks5 = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 10801)), null);
        stdSocks5.setRouteId("std");
        stdSocks5.setStandardSocks5(true);
        stdSocks5.setConfiguredWeight(1000);

        UpstreamSupport rssPeer = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 10802)), dummyRpc);
        rssPeer.setRouteId("rss");
        rssPeer.setStandardSocks5(false);
        rssPeer.setConfiguredWeight(1);

        UpstreamSupport udpClientPeer = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 10803)), dummyRpc);
        udpClientPeer.setRouteId("udpclient");
        udpClientPeer.setStandardSocks5(false);
        udpClientPeer.setUdpClient(new InetSocketAddress("127.0.0.1", 10903));
        udpClientPeer.setAdaptiveUdpClient(new InetSocketAddress("127.0.0.1", 10904));
        udpClientPeer.setConfiguredWeight(1000);

        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(stdSocks5, 1000);
        servers.add(rssPeer, 1);
        servers.add(udpClientPeer, 1000);
        RssRuntime.UpstreamSnapshot snapshot = new RssRuntime.UpstreamSnapshot(
                servers, new RandomList<DnsResolveInterceptor>(), null, null);
        assertTrue(snapshot.hasRestrictedUpstreams);

        SocksConfig protectedTcp = new SocksConfig();
        protectedTcp.setTransportFlags(TransportFlags.RSS_PSK_AES.flags());
        protectedTcp.setAdaptiveTcp(false);
        protectedTcp.setUdpHop(false);

        SocksConfig protectedUdp = new SocksConfig();
        protectedUdp.setDailyUdpFeatures(org.rx.net.rss.udp.RssDatagramHandler.AES);
        protectedUdp.setUdpHop(true);

        SocksConfig plainTcp = new SocksConfig();
        plainTcp.setTransportFlags(TransportFlags.NONE.flags());
        plainTcp.setAdaptiveTcp(false);
        plainTcp.setUdpHop(false);

        SocksContext ctx = SocksContext.getCtx(new InetSocketAddress("127.0.0.1", 50000),
                new InetSocketAddress("example.com", 80));

        // Protected TCP: neither stdSocks5 nor udpClientPeer (plain non-adaptive) is compatible -> MUST always pick rssPeer
        for (int i = 0; i < 50; i++) {
            UpstreamSupport picked = RssClient.selectCompatibleUpstream(snapshot, null, ctx, protectedTcp, true, false);
            assertSame(rssPeer, picked);
        }

        // Fail-open when rssPeer is down MUST still stay within compatible candidates (never pick stdSocks5!)
        rssPeer.setHealthy(false);
        RssClient.setWeightIfPresent(servers, rssPeer, 0);
        for (RandomList<UpstreamSupport> filtered : snapshot.filteredSocksServers.values()) {
            RssClient.setWeightIfPresent(filtered, rssPeer, 0);
        }
        UpstreamSupport failOpenPicked = RssClient.selectCompatibleUpstream(snapshot, null, ctx, protectedTcp, true, false);
        assertSame(rssPeer, failOpenPicked);
        rssPeer.setHealthy(true);
        RssClient.setWeightIfPresent(servers, rssPeer, 1);
        for (RandomList<UpstreamSupport> filtered : snapshot.filteredSocksServers.values()) {
            RssClient.setWeightIfPresent(filtered, rssPeer, 1);
        }

        // Protected UDP (with hop): only rssPeer is compatible
        for (int i = 0; i < 30; i++) {
            UpstreamSupport picked = RssClient.selectCompatibleUpstream(snapshot, null, ctx, protectedUdp, false, false);
            assertSame(rssPeer, picked);
        }

        // Plain TCP: stdSocks5 and rssPeer are compatible, udpClientPeer is excluded
        RandomList<UpstreamSupport> plainCompatible = RssClient.compatibleUpstreams(snapshot, servers, "", plainTcp, true);
        assertTrue(plainCompatible.contains(stdSocks5));
        assertTrue(plainCompatible.contains(rssPeer));
        assertFalse(plainCompatible.contains(udpClientPeer));

        // Only stdSocks5 + protected TCP -> explicit InvalidException before route execution
        RandomList<UpstreamSupport> onlyStd = new RandomList<>();
        onlyStd.add(stdSocks5, 10);
        RssRuntime.UpstreamSnapshot stdOnlySnap = new RssRuntime.UpstreamSnapshot(
                onlyStd, new RandomList<DnsResolveInterceptor>(), null, null);
        assertThrows(InvalidException.class,
                () -> RssClient.selectCompatibleUpstream(stdOnlySnap, null, ctx, protectedTcp, true, false));
    }

    @Test
    void passiveCapabilityHealth_IsolatesAdaptiveUdpAndTracksStandardSocks5() throws Exception {
        UpstreamSupport peerA = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 11001)), null);
        peerA.setRouteId("std-a");
        peerA.setStandardSocks5(true);
        peerA.setConfiguredWeight(100);

        UpstreamSupport peerB = new UpstreamSupport(
                new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 11002)), null);
        peerB.setRouteId("std-b");
        peerB.setStandardSocks5(true);
        peerB.setConfiguredWeight(1);

        RandomList<UpstreamSupport> servers = new RandomList<>();
        servers.add(peerA, 100);
        servers.add(peerB, 1);
        InetAddress src = InetAddress.getByName("127.0.0.1");

        // 3 TCP failures on standard SOCKS5 peerA degrade TCP only -> routes to peerB, while UDP on peerA remains healthy
        for (int i = 0; i < 3; i++) {
            peerA.recordFailure(UpstreamSupport.Capability.TCP, 3, 60_000L);
        }
        assertFalse(peerA.isCapabilityHealthy(UpstreamSupport.Capability.TCP));
        assertTrue(peerA.isCapabilityHealthy(UpstreamSupport.Capability.UDP));
        for (int i = 0; i < 20; i++) {
            assertSame(peerB, RssClient.nextUpstream(servers, src, null, false, 0,
                    UpstreamSupport.Capability.TCP, false));
        }

        // TCP success restores peerA immediately
        peerA.recordSuccess(UpstreamSupport.Capability.TCP);
        assertTrue(peerA.isCapabilityHealthy(UpstreamSupport.Capability.TCP));

        // ADAPTIVE_UDP degradation on peerA steers adaptive TCP away to peerB, without hurting plain TCP
        for (int i = 0; i < 3; i++) {
            peerA.recordFailure(UpstreamSupport.Capability.ADAPTIVE_UDP, 3, 60_000L);
        }
        assertFalse(peerA.isCapabilityHealthy(UpstreamSupport.Capability.ADAPTIVE_UDP));
        assertTrue(peerA.isCapabilityHealthy(UpstreamSupport.Capability.TCP));
        for (int i = 0; i < 20; i++) {
            assertSame(peerB, RssClient.nextUpstream(servers, src, null, false, 0,
                    UpstreamSupport.Capability.TCP, true));
        }

        // If BOTH peers have ADAPTIVE_UDP degraded, adaptive TCP still gracefully selects a TCP-healthy peer (does not fail)
        for (int i = 0; i < 3; i++) {
            peerB.recordFailure(UpstreamSupport.Capability.ADAPTIVE_UDP, 3, 60_000L);
        }
        UpstreamSupport fallback = RssClient.nextUpstream(servers, src, null, false, 0,
                UpstreamSupport.Capability.TCP, true);
        assertTrue(fallback == peerA || fallback == peerB);
    }

    @Test
    void rssDnsInterceptor_TracksLiveReloadBlockUserRemovalAndInFlightDrain() throws Exception {
        int[] ports = freePorts(10);
        RssClientConf conf1 = baseConf(Arrays.copyOfRange(ports, 0, 5), "dns-user", "pwd-1", null);
        RssRuntime runtime = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            runtime = new RssRuntime(ports[4] + 1200, conf1);
            CountDownLatch enteredRpc = new CountDownLatch(1);
            CountDownLatch releaseRpc = new CountDownLatch(1);
            AtomicInteger rpcCalls = new AtomicInteger();

            SocksRpcContract blockingRpc = (SocksRpcContract) Proxy.newProxyInstance(
                    SocksRpcContract.class.getClassLoader(),
                    new Class<?>[]{SocksRpcContract.class},
                    (proxy, method, args) -> {
                        if ("resolveHost".equals(method.getName())) {
                            rpcCalls.incrementAndGet();
                            enteredRpc.countDown();
                            assertTrue(releaseRpc.await(5, TimeUnit.SECONDS));
                            return Collections.singletonList(InetAddress.getByName("93.184.216.34"));
                        }
                        return null;
                    });

            UpstreamSupport rpcPeer = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", ports[3])), blockingRpc);
            rpcPeer.setRouteId("rpc-1");
            rpcPeer.setConfiguredWeight(10);
            RandomList<UpstreamSupport> rpcList = new RandomList<>();
            rpcList.add(rpcPeer, 10);
            RssRuntime.UpstreamSnapshot customSnap = new RssRuntime.UpstreamSnapshot(
                    rpcList, new RandomList<DnsResolveInterceptor>(), null, conf1.socksServers);
            RssRuntime.RuntimeGeneration oldGen = runtime.currentGeneration();
            runtime.generation = new RssRuntime.RuntimeGeneration(
                    conf1, customSnap, oldGen.dnsServer, oldGen.outboundProfiles, oldGen.standardConf);
            runtime.upstreamSnapshot = customSnap;

            ShadowUser authenticatedUser = runtime.authenticator.getShadowStore().get("dns-user");
            assertNotNull(authenticatedUser);
            RssDnsInterceptor interceptor = new RssDnsInterceptor(
                    new InetSocketAddress("8.8.8.8", 53),
                    new InetSocketAddress("127.0.0.1", 54321),
                    authenticatedUser,
                    runtime);

            // 1. In-flight DNS query retains snapshot (counted in activeConnectionCount) and releases on completion
            Future<byte[]> inFlight = executor.submit(() -> {
                DefaultDnsQuery q = newQuery("live.example", DnsRecordType.A);
                try {
                    return interceptor.answer(q, false);
                } finally {
                    q.release();
                }
            });
            assertTrue(enteredRpc.await(5, TimeUnit.SECONDS));
            assertEquals(1, customSnap.inFlightDnsQueryCount());
            assertEquals(1, RssClient.activeConnectionCount(customSnap));
            releaseRpc.countDown();
            byte[] reply1 = inFlight.get(5, TimeUnit.SECONDS);
            assertEquals(DnsResponseCode.NOERROR.intValue(), reply1[3] & 0x0f);
            assertEquals(0, customSnap.inFlightDnsQueryCount());
            assertEquals(0, RssClient.activeConnectionCount(customSnap));
            assertEquals(1, rpcCalls.get());

            // 2. Reload adding BLOCK rule -> SAME existing DNS association immediately returns REFUSED
            RssClientConf conf2 = baseConf(Arrays.copyOfRange(ports, 0, 5), "dns-user", "pwd-1", "blocked.example");
            runtime.reload(conf1, conf2);
            DefaultDnsQuery blockedQ = newQuery("blocked.example", DnsRecordType.A);
            try {
                byte[] blockedReply = interceptor.answer(blockedQ, false);
                assertEquals(DnsResponseCode.REFUSED.intValue(), blockedReply[3] & 0x0f);
            } finally {
                blockedQ.release();
            }

            // 3. Reload removing user -> SAME existing DNS association immediately returns REFUSED
            int[] conf3Ports = Arrays.copyOfRange(ports, 0, 5);
            conf3Ports[4] = ports[9];
            RssClientConf conf3 = baseConf(conf3Ports, "other-user", "pwd-1", null);
            runtime.reload(conf2, conf3);
            DefaultDnsQuery removedUserQ = newQuery("allowed.example", DnsRecordType.A);
            try {
                byte[] removedReply = interceptor.answer(removedUserQ, false);
                assertEquals(DnsResponseCode.REFUSED.intValue(), removedReply[3] & 0x0f);
            } finally {
                removedUserQ.release();
            }
        } finally {
            executor.shutdownNow();
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    @Test
    void claimUdpRelay_DoesNotLockMismatchedNatPrivateAddress() throws Exception {
        int[] ports = freePorts(2);
        String prevToken = org.rx.core.RxConfig.INSTANCE.getRtoken();
        org.rx.core.RxConfig.INSTANCE.setRtoken("nat-claim-test-token");
        org.rx.net.socks.SocksConfig svrCfg = new org.rx.net.socks.SocksConfig(ports[0]);
        svrCfg.setWhiteListEnabled(false);
        org.rx.net.socks.SocksProxyServer socksServer = new org.rx.net.socks.SocksProxyServer(svrCfg);
        org.rx.net.transport.core.TransportServer rpcServer = null;
        SocksRpcContract facade = null;
        org.rx.net.socks.Socks5Client udpClient = null;
        org.rx.net.socks.Socks5Client.Socks5UdpSession udpSession = null;
        try {
            RssRpcApp app = new RssRpcApp(socksServer);
            org.rx.net.rpc.RpcServerConfig rpcCfg = new org.rx.net.rpc.RpcServerConfig(
                    new org.rx.net.transport.TcpServerConfig(ports[1]));
            rpcCfg.getTransportConfig().setEnableAdaptiveUdp(false);
            rpcServer = org.rx.net.rpc.Remoting.register(app, rpcCfg);
            org.rx.net.rpc.RpcClientConfig<SocksRpcContract> cliCfg =
                    org.rx.net.rpc.RpcClientConfig.statefulMode("127.0.0.1:" + ports[1], 0);
            cliCfg.getTransportConfig().setEnableAdaptiveUdp(false);
            facade = org.rx.net.rpc.Remoting.createFacade(SocksRpcContract.class, cliCfg);

            udpClient = new org.rx.net.socks.Socks5Client(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", ports[0])));
            udpSession = udpClient.udpAssociateAsync().get(5, TimeUnit.SECONDS);
            int relayPort = udpSession.getRelayAddress().getPort();
            io.netty.util.AttributeKey<Boolean> lockedKey = io.netty.util.AttributeKey.valueOf("udpClientLocked");

            // 1. Mismatched NAT private IP (client behind NAT claiming 192.168.31.77 while RPC peer is 127.0.0.1)
            assertTrue(facade.claimUdpRelay(relayPort, new InetSocketAddress("192.168.31.77", 54321), "nat-claim-test-token"));
            java.lang.reflect.Field regField = org.rx.net.socks.SocksProxyServer.class.getDeclaredField("udpRelayRegistry");
            regField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Integer, io.netty.channel.Channel> registry =
                    (java.util.Map<Integer, io.netty.channel.Channel>) regField.get(socksServer);
            io.netty.channel.Channel relay = registry.get(relayPort);
            assertNotNull(relay);
            assertFalse(Boolean.TRUE.equals(relay.attr(lockedKey).get()),
                    "NAT-mismatched private clientAddr must not lock UDP relay");

            // 2. Exact peer-matching IP still locks the relay
            assertTrue(facade.claimUdpRelay(relayPort, new InetSocketAddress("127.0.0.1", 54321), "nat-claim-test-token"));
            assertTrue(Boolean.TRUE.equals(relay.attr(lockedKey).get()),
                    "Exact peer-IP clientAddr must lock UDP relay");
        } finally {
            if (udpSession != null) udpSession.close();
            if (facade != null) org.rx.core.Extends.tryClose(facade);
            if (rpcServer != null) rpcServer.close();
            socksServer.close();
            org.rx.core.RxConfig.INSTANCE.setRtoken(prevToken);
        }
    }

    private static DefaultDnsQuery newQuery(String domain, DnsRecordType type) {
        ByteBuf buf = Unpooled.buffer(128);
        try {
            DoHMessageCodec.encodeQuery(buf, 0x1234, domain, type);
            return DoHMessageCodec.decodeQuery(buf);
        } finally {
            buf.release();
        }
    }
}
