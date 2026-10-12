package org.rx.util.rss;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.rx.core.RxConfig;
import org.rx.io.FurySerializer;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.WowEndpointObservation;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.hybrid.HybridSession;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WowEndpointTest {
    static final String ID = "home-game-host";
    static final String WRITER = "unit-test-writer-key-32-characters-minimum";
    static final String QUERY = "unit-test-query-key-32-characters-minimum";

    static HybridSession peer(String ip) throws Exception {
        HybridSession peer = mock(HybridSession.class);
        when(peer.isConnected()).thenReturn(true);
        when(peer.tcpRemoteEndpoint()).thenReturn(new InetSocketAddress(InetAddress.getByName(ip), 42000));
        return peer;
    }

    static JSONObject query(WowEndpointRegistry registry, String key, int status) {
        ServerRequest request = new ServerRequest(new InetSocketAddress("127.0.0.1", 1000), "/api/wow/endpoint", HttpMethod.GET);
        request.getHeaders().set("Authorization", "Bearer " + key);
        request.getHeaders().set("X-Forwarded-For", "8.8.8.8");
        request.getQueryString().put("ip", "1.1.1.1");
        ServerResponse response = new ServerResponse();
        registry.handle(request, response);
        assertEquals(status, response.getStatus().code());
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        try { return JSON.parseObject(response.getContent().toString(StandardCharsets.UTF_8)); }
        finally { response.getContent().release(); }
    }

    @Test void identityAuthenticationFreshnessOfflineAndReadyEpoch() throws Exception {
        AtomicLong clock = new AtomicLong(1700000000000L);
        WowEndpointRegistry registry = new WowEndpointRegistry(ID, WRITER, QUERY, 45000, clock::get);
        HybridSession old = peer("58.209.44.49"), next = peer("58.209.44.50");
        assertFalse(registry.observe("other", WRITER, old, null, false));
        assertFalse(registry.observe(ID, QUERY, old, null, false));
        assertFalse(registry.observe(ID, "bad-writer", old, null, false));
        assertFalse(query(registry, QUERY, 503).containsKey("ip"));
        query(registry, "wrong-read-key", 401);
        query(registry, WRITER, 401);
        assertTrue(registry.observe(ID, WRITER, old, "58.209.44.49", true));
        JSONObject first = query(registry, QUERY, 200);
        assertEquals("58.209.44.49", first.getString("ip"));
        assertFalse(first.getBooleanValue("ready"));
        assertEquals(3724, first.getIntValue("auth_port")); assertEquals(8085, first.getIntValue("world_port"));
        assertTrue(first.getBooleanValue("online")); assertNotNull(first.getString("observed_at"));
        assertTrue(registry.observe(ID, WRITER, old, "58.209.44.49", true));
        assertTrue(query(registry, QUERY, 200).getBooleanValue("ready"));
        assertTrue(registry.observe(ID, WRITER, old, "8.8.8.8", true));
        assertFalse(query(registry, QUERY, 200).getBooleanValue("ready"));
        clock.addAndGet(45001);
        assertFalse(query(registry, QUERY, 503).containsKey("ip"));
        assertTrue(registry.observe(ID, WRITER, old, null, false));
        assertEquals(first.getString("version"), query(registry, QUERY, 200).getString("version"));
        when(old.isConnected()).thenReturn(false);
        assertFalse(query(registry, QUERY, 503).containsKey("ip"));
        assertTrue(registry.observe(ID, WRITER, next, "58.209.44.50", true));
        JSONObject second = query(registry, QUERY, 200);
        assertEquals("58.209.44.50", second.getString("ip"));
        assertNotEquals(first.getString("version"), second.getString("version"));
        assertFalse(second.getBooleanValue("ready"));
        assertTrue(registry.observe(ID, WRITER, next, "58.209.44.50", true));
        assertTrue(query(registry, QUERY, 200).getBooleanValue("ready"));
        assertEquals("58.209.44.50", registry.observePeer(ID, WRITER, next));
        assertTrue(query(registry, QUERY, 200).getBooleanValue("ready"), "fresh peer query must preserve readiness");
    }

    @Test void oldConcurrentSessionCannotOverwriteOrResurrectLatest() throws Exception {
        WowEndpointRegistry registry = new WowEndpointRegistry(ID, WRITER, QUERY, 45000);
        HybridSession old = peer("58.209.44.49"), next = peer("58.209.44.50");
        registry.observe(ID, WRITER, old, null, false);
        registry.observe(ID, WRITER, next, null, false);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        Thread a = new Thread(() -> { try { start.await(); for (int i = 0; i < 100; i++) if (registry.observe(ID, WRITER, old, null, false)) failures.incrementAndGet(); } catch (Exception e) { failures.incrementAndGet(); } finally { done.countDown(); } });
        Thread b = new Thread(() -> { try { start.await(); for (int i = 0; i < 100; i++) if (!registry.observe(ID, WRITER, next, null, false)) failures.incrementAndGet(); } catch (Exception e) { failures.incrementAndGet(); } finally { done.countDown(); } });
        a.start(); b.start(); start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, failures.get()); assertEquals("58.209.44.50", query(registry, QUERY, 200).getString("ip"));
        when(next.isConnected()).thenReturn(false);
        assertFalse(registry.observe(ID, WRITER, old, null, false));
        assertFalse(query(registry, QUERY, 503).containsKey("ip"));
    }

    @Test void disabledAndNonPublicPeersFailClosed() throws Exception {
        query(new WowEndpointRegistry(ID, "", QUERY, 45000), QUERY, 503);
        query(new WowEndpointRegistry(ID, WRITER, WRITER, 45000), WRITER, 503);
        WowEndpointRegistry registry = new WowEndpointRegistry(ID, WRITER, QUERY, 45000);
        for (String ip : new String[]{"127.0.0.1", "192.168.31.4", "10.1.2.3", "100.64.0.1", "169.254.1.1", "::1"})
            assertFalse(registry.observe(ID, WRITER, peer(ip), null, false), ip);
        assertFalse(registry.observe(ID, WRITER, null, null, false));
    }

    @Test void fixedHttpRouteUsesRegistryAndIgnoresForwardedAndQueryIp() throws Exception {
        WowEndpointRegistry registry = new WowEndpointRegistry(ID, WRITER, QUERY, 45000);
        registry.observe(ID, WRITER, peer("58.209.44.49"), null, false);
        int port = freePort();
        try (HttpServer server = new HttpServer(port, false)) {
            server.requestAsync("/api/wow/endpoint", registry);
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/wow/endpoint?ip=1.1.1.1").openConnection();
            connection.setRequestProperty("Authorization", "Bearer " + QUERY);
            connection.setRequestProperty("X-Forwarded-For", "8.8.8.8");
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            assertEquals(200, connection.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) body.append(line); }
            assertEquals("58.209.44.49", JSON.parseObject(body.toString()).getString("ip"));
            connection.disconnect();
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/wow/endpoint").openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            assertEquals(405, connection.getResponseCode()); connection.disconnect();
        }
    }

    @Test void readinessIsBoundedFreshAndNeverChangesPeer(@TempDir Path temp) throws Exception {
        long now = 1700000000000L;
        Path path = temp.resolve("ready.json");
        for (String body : new String[]{"{}", "broken", "{\"ip\":\"8.8.8.8\",\"ready\":true,\"updated_at\":1699999900}"}) {
            Files.write(path, body.getBytes(StandardCharsets.UTF_8));
            WowEndpointObservation value = new WowEndpointObservation(ID, WRITER, "token", null, false);
            WowEndpointPublisher.readReadiness(path.toString(), value, now); assertFalse(value.ready);
        }
        Files.write(path, "{\"ip\":\"58.209.44.49\",\"ready\":true,\"updated_at\":1700000000}".getBytes(StandardCharsets.UTF_8));
        WowEndpointObservation value = new WowEndpointObservation(ID, WRITER, "token", null, false);
        WowEndpointPublisher.readReadiness(path.toString(), value, now); assertTrue(value.ready); assertEquals("58.209.44.49", value.readyIp);
        assertFalse(new MethodMessage(1, WowEndpointRegistry.RPC_METHOD, new Object[]{value}, null).toString().contains(WRITER));
        assertFalse(JSON.toJSONString(value).contains(WRITER));
        WowEndpointObservation copied = FurySerializer.DEFAULT.deserializeFromBytes(FurySerializer.DEFAULT.serializeToBytes(value));
        assertEquals(WRITER, copied.writerKey); assertEquals("token", copied.rpcToken);
    }

    @Test void realStatefulRpcAuthenticatesActualPeerAndReconnectsPublisher() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken(); RxConfig.INSTANCE.setRtoken("endpoint-test-rpc-token");
        WowEndpointRegistry registry = spy(new WowEndpointRegistry(ID, WRITER, QUERY, 45000));
        RssRpcApp app = new RssRpcApp(mock(SocksProxyServer.class), null, registry);
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(freePort()));
        serverConfig.setSensitiveMethods(Collections.singleton(WowEndpointRegistry.RPC_METHOD));
        TcpServer server = Remoting.register(app, serverConfig);
        SocksRpcContract stateful = null, pool = null;
        WowEndpointPublisher publisher = null;
        try {
            RssClientConf clientConf = new RssClientConf(); clientConf.wowEndpointClientId = ID; clientConf.wowEndpointWriterKey = WRITER;
            RpcClientConfig<SocksRpcContract> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", server.getConfig().getListenPort()), SocksRpcContract.RPC_EVENT_VERSION);
            config.setRequestTimeoutMillis(3000);
            publisher = WowEndpointPublisher.configure(clientConf, config);
            stateful = Remoting.createFacade(SocksRpcContract.class, config);
            // Trigger lazy connection init; loopback must remain unpublishable even with a forged ready IP.
            WowEndpointObservation valid = new WowEndpointObservation(ID, WRITER, SocksRpcContract.rpcToken(), "58.209.44.49", true);
            assertFalse(stateful.observeWowEndpoint(valid));
            assertNull(stateful.getObservedPublicIp(valid));
            await(() -> mockingDetails(registry).getInvocations().stream().filter(i -> i.getMethod().getName().equals("observe")).count() >= 2);
            ArgumentCaptor<HybridSession> sessions = ArgumentCaptor.forClass(HybridSession.class);
            verify(registry, atLeast(2)).observe(eq(ID), eq(WRITER), sessions.capture(), any(), anyBoolean());
            HybridSession first = sessions.getValue(); assertEquals("127.0.0.1", first.tcpRemoteEndpoint().getAddress().getHostAddress()); assertTrue(first.isConnected());
            RpcClientConfig<SocksRpcContract> pooled = RpcClientConfig.poolMode(new InetSocketAddress("127.0.0.1", server.getConfig().getListenPort()), 1, 1);
            pooled.setSensitiveMethods(Collections.singleton(WowEndpointRegistry.RPC_METHOD));
            pool = Remoting.createFacade(SocksRpcContract.class, pooled);
            assertFalse(pool.observeWowEndpoint(valid)); pool.close(); pool = null;
            assertTrue(first.isConnected(), "pool lifecycle must not close persistent observation connection");
            int previousCount = mockingDetails(registry).getInvocations().size();
            first.close(); await(() -> !first.isConnected());
            await(() -> mockingDetails(registry).getInvocations().size() > previousCount);
            verify(registry, atLeast(3)).observe(eq(ID), eq(WRITER), sessions.capture(), any(), anyBoolean());
            assertNotSame(first, sessions.getValue()); assertTrue(sessions.getValue().isConnected());
            assertThrows(RuntimeException.class, () -> {
                RpcClientConfig<SocksRpcContract> wrong = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", server.getConfig().getListenPort()), 1);
                wrong.setSensitiveMethods(Collections.singleton(WowEndpointRegistry.RPC_METHOD));
                try (SocksRpcContract bad = Remoting.createFacade(SocksRpcContract.class, wrong)) { bad.observeWowEndpoint(new WowEndpointObservation(ID, WRITER, "invalid", null, false)); }
            });
        } finally {
            if (publisher != null) publisher.close(); if (stateful != null) stateful.close(); if (pool != null) pool.close(); server.close(); RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    private static int freePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void await(BooleanSupplier condition) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12); while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50); assertTrue(condition.getAsBoolean(), "condition timed out"); }
}
