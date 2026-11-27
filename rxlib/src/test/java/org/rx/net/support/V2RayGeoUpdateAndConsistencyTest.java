package org.rx.net.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rx.util.rss.RouteAction;
import org.rx.util.rss.UserRule;
import org.rx.util.rss.UserRuleMatcher;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class V2RayGeoUpdateAndConsistencyTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(20)
    public void remoteIncrementalUpdate304And200SafeReplaceAndLiveMatcherSwitch() throws Exception {
        byte[] siteV1 = V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "v1.cn")),
                V2RayGeoDataTestUtil.geoSiteEntry("ads",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "ad1.example", "promo")));
        byte[] siteV2 = V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "v2.cn")),
                V2RayGeoDataTestUtil.geoSiteEntry("ads",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "ad2.example", "promo")));
        byte[] ipV1 = V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16)));
        byte[] ipV2 = V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(5, 6, 0, 0), 16)));

        AtomicReference<byte[]> servedSite = new AtomicReference<>(siteV1);
        AtomicReference<String> siteEtag = new AtomicReference<>("\"site-v1\"");
        AtomicReference<byte[]> servedIp = new AtomicReference<>(ipV1);
        AtomicReference<String> ipEtag = new AtomicReference<>("\"ip-v1\"");
        AtomicInteger siteStatusOverride = new AtomicInteger(200);
        AtomicInteger siteRequests = new AtomicInteger();

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/geosite.dat", ex -> {
            siteRequests.incrementAndGet();
            int override = siteStatusOverride.get();
            if (override != 200) {
                ex.sendResponseHeaders(override, -1);
                ex.close();
                return;
            }
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (siteEtag.get().equals(inm)) {
                ex.getResponseHeaders().add("ETag", siteEtag.get());
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            byte[] body = servedSite.get();
            ex.getResponseHeaders().add("ETag", siteEtag.get());
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        http.createContext("/geoip.dat", ex -> {
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (ipEtag.get().equals(inm)) {
                ex.getResponseHeaders().add("ETag", ipEtag.get());
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            byte[] body = servedIp.get();
            ex.getResponseHeaders().add("ETag", ipEtag.get());
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        http.start();

        int port = http.getAddress().getPort();
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");

        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.geoIpFile = ipFile.toString();
            manager.geoSiteFile = siteFile.toString();
            manager.geoIpFileUrl = "http://127.0.0.1:" + port + "/geoip.dat";
            manager.geoSiteFileUrl = "http://127.0.0.1:" + port + "/geosite.dat";

            // 1) Initial remote download (v1)
            manager.reload();
            long genIpV1 = manager.ipGeneration();
            long genSiteV1 = manager.siteGeneration();
            assertTrue(genIpV1 > 0 && genSiteV1 > 0);
            assertNotNull(manager.getSiteDigest());

            // Compile a persistent user matcher ONCE against v1
            UserRule rule = new UserRule();
            rule.setRules(Arrays.asList(
                    "geosite:ads@promo block",
                    "geosite:cn direct",
                    "geoip:cn direct",
                    "default proxy"));
            UserRuleMatcher matcher = UserRuleMatcher.compile(rule, manager, "u1");
            assertEquals(RouteAction.BLOCK, matcher.match("x.ad1.example"));
            assertEquals(RouteAction.PROXY, matcher.match("x.ad2.example"));
            assertEquals(RouteAction.DIRECT, matcher.match("www.v1.cn"));
            assertEquals(RouteAction.PROXY, matcher.match("www.v2.cn"));
            assertEquals(RouteAction.DIRECT, matcher.match("1.2.3.4"));
            assertEquals(RouteAction.PROXY, matcher.match("5.6.7.8"));

            // 2) reloadAsync() MUST actually check remote URL -> HTTP 304 -> no rebuild, same generation!
            int beforeReqs = siteRequests.get();
            manager.reloadAsync();
            manager.ipTask.get(10, TimeUnit.SECONDS);
            manager.siteTask.get(10, TimeUnit.SECONDS);
            assertTrue(siteRequests.get() > beforeReqs, "reloadAsync must actually query remote URL");
            assertEquals(genIpV1, manager.ipGeneration(), "304 must not rebuild ip index");
            assertEquals(genSiteV1, manager.siteGeneration(), "304 must not rebuild site index");
            assertTrue(manager.getNotModifiedCount() >= 2);

            // 3) Corrupt download (invalid protobuf) -> MUST preserve BOTH in-memory v1 AND on-disk v1 file!
            servedSite.set(new byte[]{0, 1, 2, 3, 4});
            siteEtag.set("\"site-corrupt\"");
            assertThrows(RuntimeException.class, manager::reload);
            assertEquals(genSiteV1, manager.siteGeneration());
            assertArrayEquals(siteV1, Files.readAllBytes(siteFile), "corrupt download must not overwrite valid disk file");
            assertEquals(RouteAction.DIRECT, matcher.match("www.v1.cn"));

            // 4) HTTP 500 error -> preserves old memory & disk
            siteStatusOverride.set(500);
            assertThrows(Exception.class, manager::reload);
            assertEquals(genSiteV1, manager.siteGeneration());
            assertEquals(RouteAction.DIRECT, matcher.match("www.v1.cn"));

            // 5) Oversized response rejected -> preserves old memory & disk
            siteStatusOverride.set(200);
            servedSite.set(siteV2);
            siteEtag.set("\"site-v2\"");
            manager.setMaxFileSizeBytes(10L);
            assertThrows(Exception.class, manager::reload);
            assertEquals(genSiteV1, manager.siteGeneration());
            assertArrayEquals(siteV1, Files.readAllBytes(siteFile));
            manager.setMaxFileSizeBytes(V2RayGeoManager.DEFAULT_MAX_FILE_SIZE_BYTES);

            // 6) Valid v2 update via reloadAsync -> SAME compiled matcher automatically switches from Geo A -> B!
            servedIp.set(ipV2);
            ipEtag.set("\"ip-v2\"");
            manager.reloadAsync();
            manager.ipTask.get(10, TimeUnit.SECONDS);
            manager.siteTask.get(10, TimeUnit.SECONDS);
            assertTrue(manager.ipGeneration() > genIpV1);
            assertTrue(manager.siteGeneration() > genSiteV1);
            assertArrayEquals(siteV2, Files.readAllBytes(siteFile));
            assertArrayEquals(ipV2, Files.readAllBytes(ipFile));

            // Same compiled UserRuleMatcher now reflects v2 immediately!
            assertEquals(RouteAction.PROXY, matcher.match("x.ad1.example"));
            assertEquals(RouteAction.BLOCK, matcher.match("x.ad2.example"));
            assertEquals(RouteAction.PROXY, matcher.match("www.v1.cn"));
            assertEquals(RouteAction.DIRECT, matcher.match("www.v2.cn"));
            assertEquals(RouteAction.PROXY, matcher.match("1.2.3.4"));
            assertEquals(RouteAction.DIRECT, matcher.match("5.6.7.8"));

            // Shared matcher identity across users on same snapshot generation
            assertSame(manager.tryCompileGeoIpMatcher("cn"), manager.tryCompileGeoIpMatcher("GEOIP:CN"));
            assertSame(manager.tryCompileGeoSiteMatcher("cn"), manager.tryCompileGeoSiteMatcher("geosite:CN"));
        } finally {
            manager.close();
            http.stop(0);
        }
    }

    @Test
    public void coldStartBlockRuleFailsClosedUntilGeoLoadedAndPureLocalMakesZeroNetworkCalls() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");

        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.geoIpFileUrl = null;
            manager.geoSiteFileUrl = null;
            manager.geoIpFile = ipFile.toString();
            manager.geoSiteFile = siteFile.toString();

            UserRule rule = new UserRule();
            rule.setRules(Arrays.asList(
                    "geosite:ads block",
                    "geoip:bad block",
                    "geosite:cn direct",
                    "default proxy"));
            UserRuleMatcher matcher = UserRuleMatcher.compile(rule, manager, "u-cold");

            // Cold start before Geo files exist: BLOCK rules fail CLOSED (must NOT silently allow as PROXY!)
            assertEquals(RouteAction.BLOCK, matcher.match("anything.example"));
            assertEquals(RouteAction.BLOCK, matcher.match("8.8.8.8"));

            // Once valid local files appear and reloadLocal runs, exact matching takes effect
            Files.write(siteFile, V2RayGeoDataTestUtil.geoSiteList(
                    V2RayGeoDataTestUtil.geoSiteEntry("ads",
                            V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "ad.example")),
                    V2RayGeoDataTestUtil.geoSiteEntry("cn",
                            V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "cn.example"))));
            Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                    V2RayGeoDataTestUtil.geoIpEntry("bad", false,
                            V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 99, 0, 0), 16))));
            manager.reloadLocal();

            assertEquals(RouteAction.BLOCK, matcher.match("a.ad.example"));
            assertEquals(RouteAction.DIRECT, matcher.match("a.cn.example"));
            assertEquals(RouteAction.PROXY, matcher.match("clean.example"));
            assertEquals(RouteAction.BLOCK, matcher.match("10.99.1.1"));
            assertEquals(RouteAction.PROXY, matcher.match("8.8.8.8"));
        } finally {
            manager.close();
        }
    }
}
