package org.rx.net.http;

import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.multipart.FileUpload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rx.io.EntityDatabaseImpl;
import org.rx.io.IOStream;
import org.rx.net.Sockets;
import org.rx.net.socks.DefaultSocksAuthenticator;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksUser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.File;
import java.net.Proxy;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientV2Test {
    private static final int PORT = 18082;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static HttpServer server;

    @BeforeAll
    public static void setup() {
        server = new HttpServer(PORT, false);
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

    @Test
    public void testGet() {
        try (HttpClientV2 client = new HttpClientV2().withFeatures(false, false)) {
            HttpClientV2.ResponseContent response = client.get(BASE_URL + "/get");
            assertEquals(200, response.getStatusCode());
            assertTrue(response.toString().contains("ok-v2"));
        }
    }

    @Test
    public void testPostJson() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "alpha");
        try (HttpClientV2 client = new HttpClientV2().withFeatures(false, false)) {
            HttpClientV2.ResponseContent response = client.postJson(BASE_URL + "/json", body);
            assertTrue(response.toString().contains("json-ok"));
        }
    }

    @Test
    public void testPostForm() {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "rx");
        form.put("age", 8);
        try (HttpClientV2 client = new HttpClientV2().withFeatures(false, false)) {
            HttpClientV2.ResponseContent response = client.post(BASE_URL + "/form", form);
            assertTrue(response.toString().contains("rx:8"));
        }
    }

    @Test
    public void testMultipartUpload() {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "rx");
        Map<String, IOStream> files = Collections.singletonMap("file", IOStream.wrap("upload.txt", "hello-v2".getBytes()));
        try (HttpClientV2 client = new HttpClientV2().withFeatures(false, false)) {
            HttpClientV2.ResponseContent response = client.post(BASE_URL + "/upload", form, files);
            assertTrue(response.toString().contains("rx:upload.txt:hello-v2"));
        }
    }

    @Test
    public void testCookieRoundTrip() {
        HttpClientV2.HttpClientCookieJar jar = new HttpClientV2.HttpClientCookieJar();
        try (HttpClientV2 client = new HttpClientV2(jar).withFeatures(true, false)) {
            assertTrue(client.get(BASE_URL + "/cookie-set").toString().contains("cookie-set"));
            assertTrue(client.get(BASE_URL + "/cookie-check").toString().contains("v2"));
        }
    }

    @Test
    public void testConfigConstructor() {
        HttpClientConfig config = HttpClientConfig.defaults()
                .withFeatures(true, false)
                .withPool(2, 3, 400)
                .withCookieJar(new HttpClientV2.HttpClientCookieJar());
        try (HttpClientV2 client = new HttpClientV2(config)) {
            assertEquals(2, client.config().getMaxConnectionsPerHost());
            assertEquals(3, client.config().getPendingAcquireMaxCount());
            assertEquals(400, client.config().getAcquireTimeoutMillis());
            assertTrue(client.get(BASE_URL + "/cookie-set").toString().contains("cookie-set"));
            assertTrue(client.get(BASE_URL + "/cookie-check").toString().contains("v2"));
        }
    }

    @Test
    public void testH2CookieStorage() throws Exception {
        File file = File.createTempFile("rx-http-cookie", "");
        String path = file.getAbsolutePath();
        assertTrue(file.delete());
        EntityDatabaseImpl db = new EntityDatabaseImpl(path, null, 1);
        try {
            HttpClientV2.HttpClientCookieJar jar = HttpClientV2.HttpClientCookieJar.h2(db);
            try (HttpClientV2 client = new HttpClientV2(jar).withFeatures(true, false)) {
                assertTrue(client.get(BASE_URL + "/cookie-persistent-set").toString().contains("cookie-persistent-set"));
            }

            HttpClientV2.HttpClientCookieJar reloaded = HttpClientV2.HttpClientCookieJar.h2(db);
            try (HttpClientV2 client = new HttpClientV2(reloaded).withFeatures(true, false)) {
                assertTrue(client.get(BASE_URL + "/cookie-check").toString().contains("persist-v2"));
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

        try (HttpClientV2 client = new HttpClientV2().withFeatures(false, false)) {
            client.forward(request, response, BASE_URL + "/forward-json");
            assertEquals(200, response.getStatus());
            assertEquals("1", response.getHeader("X-Forward-OK"));
            assertTrue(response.getContentAsString().contains("forward-ok"));
        }
    }

    @Test
    public void testSocksProxy() throws Exception {
        int proxyPort = freePort();
        SocksUser user = new SocksUser("u1");
        user.setPassword("p1");
        user.setIpLimit(-1);
        SocksProxyServer proxyServer = new SocksProxyServer(new SocksConfig(Sockets.newLoopbackEndpoint(proxyPort)),
                new DefaultSocksAuthenticator(Collections.singletonList(user)));
        try {
            waitProxy(proxyServer);
            AuthenticProxy proxy = new AuthenticProxy(Proxy.Type.SOCKS, Sockets.newLoopbackEndpoint(proxyPort), "u1", "p1");
            try (HttpClientV2 client = new HttpClientV2().withProxy(proxy).withFeatures(false, false)) {
                HttpClientV2.ResponseContent response = client.get(BASE_URL + "/get");
                assertEquals(200, response.getStatusCode());
                assertTrue(response.toString().contains("ok-v2"));
            }

            AuthenticProxy badProxy = new AuthenticProxy(Proxy.Type.SOCKS, Sockets.newLoopbackEndpoint(proxyPort), "u1", "bad");
            try (HttpClientV2 client = new HttpClientV2().withTimeoutMillis(500).withProxy(badProxy).withFeatures(false, false)) {
                assertThrows(Exception.class, () -> client.get(BASE_URL + "/get"));
                assertTrue(client.metrics().failed() > 0);
            }
        } finally {
            proxyServer.close();
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
