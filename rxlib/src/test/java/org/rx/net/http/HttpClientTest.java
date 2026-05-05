package org.rx.net.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.multipart.FileUpload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rx.io.DuplexStream;
import org.rx.io.EntityDatabaseImpl;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksAuthenticator;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksUser;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientTest {
    private static HttpServer server;
    private static final int PORT = 18080;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;

    @BeforeAll
    public static void setup() {
        server = new HttpServer(PORT, false);
        server.requestMapping("/test", (req, res) -> res.jsonBody("ok"));
        server.requestMapping("/large", (req, res) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("Hello World ");
            }
            res.jsonBody(sb.toString());
        });
        server.requestMapping("/get", (req, res) -> res.jsonBody("ok-v2"));
        server.requestMapping("/json", (req, res) -> {
            assertTrue(req.jsonBody().contains("alpha"));
            res.jsonBody("json-ok");
        });
        server.requestMapping("/forward-json", (req, res) -> {
            assertTrue(req.jsonBody().contains("forward"));
            res.getHeaders().set("X-Forward-OK", "1");
            res.jsonBody("forward-ok");
        });
        server.requestMapping("/form", (req, res) -> {
            assertNotNull(req.getForm());
            res.jsonBody(req.getForm().getFirst("name") + ":" + req.getForm().getFirst("age"));
        });
        server.requestMapping("/upload", (req, res) -> {
            assertNotNull(req.getForm());
            assertNotNull(req.getFiles());
            FileUpload file = req.getFiles().getFirst("file");
            assertNotNull(file);
            res.jsonBody(req.getForm().getFirst("name") + ":" + file.getFilename() + ":" + file.getString());
        });
        server.requestMapping("/cookie-set", (req, res) -> {
            res.addCookie(new DefaultCookie("sid", "v2"));
            res.jsonBody("cookie-set");
        });
        server.requestMapping("/cookie-persistent-set", (req, res) -> {
            DefaultCookie cookie = new DefaultCookie("sid", "persist-v2");
            cookie.setMaxAge(3600);
            res.addCookie(cookie);
            res.jsonBody("cookie-persistent-set");
        });
        server.requestMapping("/cookie-check", (req, res) -> res.jsonBody(req.getCookies().isEmpty() ? "none" : req.getCookies().iterator().next().value()));
    }

    @AfterAll
    public static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static HttpClient clientNoCookieNoLog() {
        return new HttpClient(new HttpClientConfig().setCookieJar(null).setEnableLog(false));
    }

    @Test
    public void testAutoCloseable() throws Exception {
        String url = BASE_URL + "/test";
        InputStream stream;
        try (HttpClient client = new HttpClient()) {
            try (HttpClient.Response res = client.get(url)) {
                stream = res.bodyStream().asInputStream();
                assertNotNull(stream);
                assertTrue(stream.read() != -1);
            }
        }

        assertThrows(Exception.class, () -> stream.read(), "Stream should be closed after response.close()");
    }

    @Test
    public void metricsShouldBeLazyUntilRequested() throws Exception {
        try (HttpClient client = new HttpClient()) {
            Field field = HttpClient.class.getDeclaredField("metrics");
            field.setAccessible(true);
            assertNull(field.get(client));

            HttpClient.Metrics metrics = client.getMetrics();
            assertNotNull(metrics);
            assertSame(metrics, field.get(client));
        }
    }

    @Test
    public void testMultipleConsumption() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            try (HttpClient.Response res = client.get(url)) {
                String s1 = res.bodyAsString();
                assertTrue(s1.contains("ok"));

                String s2 = res.bodyAsString();
                assertEquals(s1, s2);
            }
        }
    }

    @Test
    public void testConsecutiveRequests() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            try (HttpClient.Response res1 = client.get(url);
                    HttpClient.Response res2 = client.get(url)) {
                assertTrue(res1.bodyAsString().contains("ok"));
                assertTrue(res2.bodyAsString().contains("ok"));
            }
        }
    }

    @Test
    public void testMultipleConsumptionWithTwoMethods() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            try (HttpClient.Response res = client.get(url)) {
                String s1 = res.bodyAsString();
                assertTrue(s1.contains("ok"));

                assertDoesNotThrow(() -> res.bodyAsFile("test.tmp"),
                        "Should be able to call bodyAsFile() after bodyAsString()");
            }
        } finally {
            new java.io.File("test.tmp").delete();
        }
    }

    @Test
    public void testGet() {
        try (HttpClient client = clientNoCookieNoLog()) {
            try (HttpClient.Response response = client.get(BASE_URL + "/get")) {
                assertEquals(200, response.code());
                assertTrue(response.bodyAsString().contains("ok-v2"));
            }
        }
    }

    @Test
    public void testPostJson() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "alpha");
        try (HttpClient client = clientNoCookieNoLog()) {
            try (HttpClient.Response response = client.postJson(BASE_URL + "/json", body)) {
                assertTrue(response.bodyAsString().contains("json-ok"));
            }
        }
    }

    @Test
    public void testPostForm() {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "rx");
        form.put("age", 8);
        try (HttpClient client = clientNoCookieNoLog()) {
            try (HttpClient.Response response = client.post(BASE_URL + "/form", form)) {
                assertTrue(response.bodyAsString().contains("rx:8"));
            }
        }
    }

    @Test
    public void testMultipartUpload() {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "rx");
        Map<String, DuplexStream> files = Collections.singletonMap("file", DuplexStream.wrap("upload.txt", "hello-v2".getBytes()));
        try (HttpClient client = clientNoCookieNoLog()) {
            try (HttpClient.Response response = client.post(BASE_URL + "/upload", form, files)) {
                assertTrue(response.bodyAsString().contains("rx:upload.txt:hello-v2"));
            }
        }
    }

    @Test
    public void testCookieRoundTrip() {
        HttpClientCookieJar jar = new HttpClientCookieJar();
        try (HttpClient client = new HttpClient(new HttpClientConfig().setCookieJar(jar).setEnableLog(false))) {
            try (HttpClient.Response set = client.get(BASE_URL + "/cookie-set");
                    HttpClient.Response check = client.get(BASE_URL + "/cookie-check")) {
                assertTrue(set.bodyAsString().contains("cookie-set"));
                assertTrue(check.bodyAsString().contains("v2"));
            }
        }
    }

    @Test
    public void testConfigConstructor() {
        HttpClientConfig config = new HttpClientConfig()
                .setEnableLog(false)
                .setPool(2, 3, 400)
                .setCookieJar(new HttpClientCookieJar());
        try (HttpClient client = new HttpClient(config)) {
            assertEquals(2, client.config().getMaxConnectionsPerHost());
            assertEquals(3, client.config().getPendingAcquireMaxCount());
            assertEquals(400, client.config().getAcquireTimeoutMillis());
            try (HttpClient.Response set = client.get(BASE_URL + "/cookie-set");
                    HttpClient.Response check = client.get(BASE_URL + "/cookie-check")) {
                assertTrue(set.bodyAsString().contains("cookie-set"));
                assertTrue(check.bodyAsString().contains("v2"));
            }
        }
    }

    @Test
    public void testCookieIdentityIncludesSecureAndHostOnly() {
        HttpClientCookieJar jar = HttpClientCookieJar.memory();
        URI uri = URI.create("http://example.com/path");

        DefaultCookie hostOnly = new DefaultCookie("sid", "h1");
        hostOnly.setPath("/");
        jar.save(uri, hostOnly);

        DefaultCookie hostOnlyNewValue = new DefaultCookie("sid", "h2");
        hostOnlyNewValue.setPath("/");
        jar.save(uri, hostOnlyNewValue);

        DefaultCookie domain = new DefaultCookie("sid", "d");
        domain.setDomain("example.com");
        domain.setPath("/");
        jar.save(uri, domain);

        DefaultCookie secure = new DefaultCookie("sid", "s");
        secure.setDomain("example.com");
        secure.setPath("/");
        secure.setSecure(true);
        jar.save(URI.create("https://example.com/path"), secure);

        assertEquals(2, jar.cookies.size());
        String exactHostCookie = jar.loadForRequest(uri);
        assertTrue(exactHostCookie.contains("sid=h2"));
        assertFalse(exactHostCookie.contains("sid=d"));
        assertFalse(exactHostCookie.contains("sid=s"));

        String subDomainCookie = jar.loadForRequest(URI.create("http://sub.example.com/path"));
        assertNull(subDomainCookie);

        String httpsCookie = jar.loadForRequest(URI.create("https://example.com/path"));
        assertTrue(httpsCookie.contains("sid=h2"));
        assertTrue(httpsCookie.contains("sid=s"));
    }

    @Test
    public void testCookieBrowserDomainAndPathMatching() {
        HttpClientCookieJar jar = HttpClientCookieJar.memory();
        URI origin = URI.create("http://sub.example.com/docs/page");

        DefaultCookie badDomain = new DefaultCookie("bad", "1");
        badDomain.setDomain("evil.com");
        badDomain.setPath("/");
        jar.save(origin, badDomain);
        assertNull(jar.loadForRequest(URI.create("http://evil.com/")));

        DefaultCookie domain = new DefaultCookie("domain", "ok");
        domain.setDomain(".example.com");
        domain.setPath("/");
        jar.save(origin, domain);
        assertTrue(jar.loadForRequest(URI.create("http://deep.example.com/")).contains("domain=ok"));

        DefaultCookie scoped = new DefaultCookie("scoped", "v");
        jar.save(origin, scoped);
        assertTrue(jar.loadForRequest(URI.create("http://sub.example.com/docs/child")).contains("scoped=v"));
        assertFalse(String.valueOf(jar.loadForRequest(URI.create("http://sub.example.com/other"))).contains("scoped=v"));

        DefaultCookie docsPath = new DefaultCookie("docs", "v");
        docsPath.setPath("/docs");
        jar.save(origin, docsPath);
        assertTrue(jar.loadForRequest(URI.create("http://sub.example.com/docs/page")).contains("docs=v"));
        assertFalse(String.valueOf(jar.loadForRequest(URI.create("http://sub.example.com/docs2/page"))).contains("docs=v"));

        DefaultCookie root = new DefaultCookie("id", "root");
        root.setDomain(".example.com");
        root.setPath("/");
        jar.save(origin, root);
        DefaultCookie nested = new DefaultCookie("id", "nested");
        nested.setDomain(".example.com");
        nested.setPath("/docs");
        jar.save(origin, nested);
        String cookieHeader = jar.loadForRequest(URI.create("http://sub.example.com/docs/page"));
        assertTrue(cookieHeader.indexOf("id=nested") < cookieHeader.indexOf("id=root"));
    }

    @Test
    public void testCookieBrowserRejectRules() {
        HttpClientCookieJar jar = HttpClientCookieJar.memory();

        DefaultCookie publicSuffix = new DefaultCookie("psl", "1");
        publicSuffix.setDomain("github.io");
        publicSuffix.setPath("/");
        jar.save(URI.create("https://app.github.io/"), publicSuffix);
        assertNull(jar.loadForRequest(URI.create("https://app.github.io/")));

        DefaultCookie topLevel = new DefaultCookie("top", "1");
        topLevel.setDomain("com");
        topLevel.setPath("/");
        jar.save(URI.create("https://example.com/"), topLevel);
        assertNull(jar.loadForRequest(URI.create("https://example.com/")));

        DefaultCookie secureFromHttp = new DefaultCookie("sec", "1");
        secureFromHttp.setSecure(true);
        secureFromHttp.setPath("/");
        jar.save(URI.create("http://example.com/"), secureFromHttp);
        assertNull(jar.loadForRequest(URI.create("https://example.com/")));

        DefaultCookie sameSiteNone = new DefaultCookie("none", "1");
        sameSiteNone.setSameSite(CookieHeaderNames.SameSite.None);
        sameSiteNone.setPath("/");
        jar.save(URI.create("https://example.com/"), sameSiteNone);
        assertNull(jar.loadForRequest(URI.create("https://example.com/")));

        DefaultCookie badSecurePrefix = new DefaultCookie("__Secure-id", "1");
        badSecurePrefix.setPath("/");
        jar.save(URI.create("https://example.com/"), badSecurePrefix);
        assertNull(jar.loadForRequest(URI.create("https://example.com/")));

        DefaultCookie badHostPrefix = new DefaultCookie("__Host-id", "1");
        badHostPrefix.setSecure(true);
        badHostPrefix.setDomain("example.com");
        badHostPrefix.setPath("/");
        jar.save(URI.create("https://example.com/"), badHostPrefix);
        assertNull(jar.loadForRequest(URI.create("https://example.com/")));

        DefaultCookie hostPrefix = new DefaultCookie("__Host-id", "2");
        hostPrefix.setSecure(true);
        hostPrefix.setPath("/");
        jar.save(URI.create("https://example.com/"), hostPrefix);
        assertTrue(jar.loadForRequest(URI.create("https://example.com/")).contains("__Host-id=2"));

        DefaultCookie partitionedNoSecure = new DefaultCookie("part", "bad");
        partitionedNoSecure.setPath("/");
        partitionedNoSecure.setPartitioned(true);
        jar.save(URI.create("https://cdn.example.com/"), partitionedNoSecure);
        assertFalse(String.valueOf(jar.loadForRequest(URI.create("https://cdn.example.com/"))).contains("part=bad"));

        DefaultCookie partitioned = new DefaultCookie("part", "ok");
        partitioned.setSecure(true);
        partitioned.setPath("/");
        partitioned.setPartitioned(true);
        partitioned.setSameSite(CookieHeaderNames.SameSite.None);
        URI widget = URI.create("https://cdn.example.com/");
        URI siteA = URI.create("https://shop.example/");
        URI siteB = URI.create("https://news.example/");
        jar.save(widget, partitioned, HttpClientCookieJar.SameSiteContext.CROSS_SITE_TOP_LEVEL_SAFE, true, siteA);
        assertTrue(jar.loadForRequest(widget, HttpClientCookieJar.SameSiteContext.CROSS_SITE, siteA).contains("part=ok"));
        assertFalse(String.valueOf(jar.loadForRequest(widget, HttpClientCookieJar.SameSiteContext.CROSS_SITE, siteB)).contains("part=ok"));
    }

    @Test
    public void testCookieSameSiteRetrievalAndSecureOverlay() {
        HttpClientCookieJar jar = HttpClientCookieJar.memory();

        DefaultCookie strict = new DefaultCookie("strict", "1");
        strict.setPath("/");
        strict.setSameSite(CookieHeaderNames.SameSite.Strict);
        jar.save(URI.create("https://example.com/"), strict);

        DefaultCookie lax = new DefaultCookie("lax", "1");
        lax.setPath("/");
        lax.setSameSite(CookieHeaderNames.SameSite.Lax);
        jar.save(URI.create("https://example.com/"), lax);

        String topLevelSafe = jar.loadForRequest(URI.create("https://example.com/"), HttpClientCookieJar.SameSiteContext.CROSS_SITE_TOP_LEVEL_SAFE);
        assertFalse(topLevelSafe.contains("strict=1"));
        assertTrue(topLevelSafe.contains("lax=1"));
        String crossSite = jar.loadForRequest(URI.create("https://example.com/"), HttpClientCookieJar.SameSiteContext.CROSS_SITE);
        assertFalse(String.valueOf(crossSite).contains("strict=1"));
        assertFalse(String.valueOf(crossSite).contains("lax=1"));

        DefaultCookie crossSiteSet = new DefaultCookie("crossSet", "1");
        crossSiteSet.setPath("/");
        crossSiteSet.setSameSite(CookieHeaderNames.SameSite.Lax);
        jar.save(URI.create("https://example.com/"), crossSiteSet, HttpClientCookieJar.SameSiteContext.CROSS_SITE, false);
        assertFalse(String.valueOf(jar.loadForRequest(URI.create("https://example.com/"))).contains("crossSet=1"));

        DefaultCookie secure = new DefaultCookie("sid", "secure");
        secure.setSecure(true);
        secure.setPath("/login");
        jar.save(URI.create("https://example.com/login"), secure);

        DefaultCookie overlay = new DefaultCookie("sid", "plain");
        overlay.setPath("/login/en");
        jar.save(URI.create("http://example.com/login/en"), overlay);

        String secureHeader = jar.loadForRequest(URI.create("https://example.com/login/en"));
        assertTrue(secureHeader.contains("sid=secure"));
        assertFalse(secureHeader.contains("sid=plain"));
    }

    @Test
    public void testH2CookieStorage() throws Exception {
        File file = File.createTempFile("rx-http-cookie", "");
        String path = file.getAbsolutePath();
        assertTrue(file.delete());
        EntityDatabaseImpl db = new EntityDatabaseImpl(path, null, 1);
        try {
            HttpClientCookieJar jar = HttpClientCookieJar.storage(db);
            try (HttpClient client = new HttpClient(new HttpClientConfig().setCookieJar(jar).setEnableLog(false))) {
                try (HttpClient.Response response = client.get(BASE_URL + "/cookie-persistent-set")) {
                    assertTrue(response.bodyAsString().contains("cookie-persistent-set"));
                }
            }

            HttpClientCookieJar reloaded = HttpClientCookieJar.storage(db);
            try (HttpClient client = new HttpClient(new HttpClientConfig().setCookieJar(reloaded).setEnableLog(false))) {
                try (HttpClient.Response response = client.get(BASE_URL + "/cookie-check")) {
                    assertTrue(response.bodyAsString().contains("persist-v2"));
                }
            }
        } finally {
            db.close();
            new File(path + ".mv.db").delete();
            new File(path + ".trace.db").delete();
        }
    }

    @Test
    public void testForward() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/proxy");
        request.setContentType(ServerResponse.APPLICATION_JSON.toString());
        request.setContent("{\"forward\":true}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (HttpClient client = clientNoCookieNoLog()) {
            client.forward(request, response, BASE_URL + "/forward-json");
            assertEquals(200, response.getStatus());
            assertEquals("1", response.getHeader("X-Forward-OK"));
            assertTrue(response.getContentAsString().contains("forward-ok"));
        }
    }

    @Test
    public void testUploadWriterRejectsEventLoopThread() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = new NioSocketChannel();
        try {
            group.register(channel).syncUninterruptibly();
            channel.eventLoop().submit(() ->
                    assertThrows(IllegalStateException.class, () -> new HttpClient.UploadWriter(channel, null)))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            channel.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void testSocksProxy() throws Exception {
        int proxyPort = freePort();
        SocksUser user = new SocksUser("u1");
        user.setPassword("p1");
        SocksProxyServer proxyServer = new SocksProxyServer(new SocksConfig(Sockets.newLoopbackEndpoint(proxyPort)),
                new SocksAuthenticator(Collections.singletonList(user)));
        try {
            waitProxy(proxyServer);
            AuthenticProxy proxy = new AuthenticProxy(Proxy.Type.SOCKS, Sockets.newLoopbackEndpoint(proxyPort), "u1", "p1");
            try (HttpClient client = new HttpClient(new HttpClientConfig().setProxy(proxy).setCookieJar(null).setEnableLog(false))) {
                try (HttpClient.Response response = client.get(BASE_URL + "/get")) {
                    assertEquals(200, response.code());
                    assertTrue(response.bodyAsString().contains("ok-v2"));
                }
            }

            AuthenticProxy badProxy = new AuthenticProxy(Proxy.Type.SOCKS, Sockets.newLoopbackEndpoint(proxyPort), "u1", "bad");
            try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(500).setProxy(badProxy).setCookieJar(null).setEnableLog(false))) {
                assertThrows(Exception.class, () -> client.get(BASE_URL + "/get"));
                assertTrue(client.getMetrics().failed() > 0);
            }
        } finally {
            proxyServer.close();
        }
    }

    @Test
    public void testEnableLogFormat() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HttpClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (HttpClient client = new HttpClient(new HttpClientConfig().setEnableLog(true).setCookieJar(null))) {
            try (HttpClient.Response response = client.get(BASE_URL + "/get")) {
                assertEquals(200, response.code());
                assertTrue(response.bodyAsString().contains("ok-v2"));
            }
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(p -> p.contains("HTTP GET " + BASE_URL + "/get -> 200") && p.contains("req=") && p.contains("res=ok-v2")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    public void testRequestEnableLogOverridesClientConfig() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HttpClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (HttpClient client = new HttpClient(new HttpClientConfig().setEnableLog(false).setCookieJar(null))) {
            HttpClient.Request request = HttpClient.request(io.netty.handler.codec.http.HttpMethod.GET, BASE_URL + "/get")
                    .enableLog(true);
            try (HttpClient.Response response = client.execute(request)) {
                assertEquals(200, response.code());
                assertTrue(response.bodyAsString().contains("ok-v2"));
            }
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(p -> p.contains("HTTP GET " + BASE_URL + "/get -> 200") && p.contains("req=") && p.contains("res=ok-v2")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static int freePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static void waitProxy(SocksProxyServer proxyServer) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (proxyServer.isBind()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("SOCKS proxy bind timeout");
    }
}
