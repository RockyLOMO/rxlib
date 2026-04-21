package org.rx.net.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;

import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientV2IntegrationTest {
    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    public static void setup() throws Exception {
        int port = freePort();
        baseUrl = "http://127.0.0.1:" + port;
        server = new HttpServer(port, false);
        server.requestMapping("/get", (req, res) ->
                res.htmlBody(req.getMethod().name() + ":" + req.getQueryString().getFirst("q")));
        server.requestMapping("/head", (req, res) -> res.setStatus(io.netty.handler.codec.http.HttpResponseStatus.OK));
        server.requestMapping("/json", (req, res) ->
                res.htmlBody(req.getMethod().name() + ":" + req.jsonBody()));
        server.requestMapping("/form", (req, res) ->
                res.htmlBody(req.getMethod().name() + ":" + req.getForm().getFirst("a") + ":" + req.getForm().getFirst("b")));
        server.requestMapping("/multipart", (req, res) -> {
            FileUpload upload = req.getFiles().getFirst("file");
            res.htmlBody(req.getForm().getFirst("name") + ":" + upload.getFilename() + ":" + upload.getString(CharsetUtil.UTF_8));
        });
        server.requestMapping("/cookie-set", (req, res) -> {
            res.getHeaders().set(HttpHeaderNames.SET_COOKIE, "sid=abc; Path=/");
            res.htmlBody("set");
        });
        server.requestMapping("/cookie-echo", (req, res) ->
                res.htmlBody(String.valueOf(req.getHeaders().get(HttpHeaderNames.COOKIE))));
        server.requestMapping("/large", (req, res) -> {
            StringBuilder sb = new StringBuilder(16384);
            for (int i = 0; i < 2048; i++) {
                sb.append("rxlib");
            }
            res.htmlBody(sb.toString());
        });
    }

    @AfterAll
    public static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void getHeadAndMetrics() {
        try (HttpClientV2 client = new HttpClientV2()) {
            HttpClientV2.Response response = client.get(baseUrl + "/get?q=ok");
            assertEquals(200, response.getStatusCode());
            assertEquals("GET:ok", response.toString());

            HttpClientV2.Response head = client.head(baseUrl + "/head");
            assertEquals(200, head.getStatusCode());
            assertEquals(2, client.metrics().requests());
            assertEquals(2, client.metrics().success());
            assertTrue(client.metrics().maxLatencyNanos() > 0);
            assertTrue(client.metrics().usedDirectMemory() >= 0);
        }
    }

    @Test
    public void jsonBodyWorksForPostPutPatchDelete() {
        Map<String, Object> json = new HashMap<>();
        json.put("hello", "world");

        try (HttpClientV2 client = new HttpClientV2()) {
            assertTrue(client.postJson(baseUrl + "/json", json).toString().contains("POST:{\"hello\":\"world\"}"));
            assertTrue(client.putJson(baseUrl + "/json", json).toString().contains("PUT:{\"hello\":\"world\"}"));
            assertTrue(client.patchJson(baseUrl + "/json", json).toString().contains("PATCH:{\"hello\":\"world\"}"));
            assertTrue(client.deleteJson(baseUrl + "/json", json).toString().contains("DELETE:{\"hello\":\"world\"}"));
        }
    }

    @Test
    public void formAndMultipartBodiesAreDecodedByServer() {
        Map<String, Object> forms = new HashMap<>();
        forms.put("a", "1");
        forms.put("b", "two words");

        Map<String, Object> multipartForms = new HashMap<>();
        multipartForms.put("name", "n1");
        Map<String, IOStream> files = new HashMap<>();
        files.put("file", IOStream.wrap("hello.txt", "file-body".getBytes(CharsetUtil.UTF_8)));

        try (HttpClientV2 client = new HttpClientV2()) {
            assertEquals("POST:1:two words", client.post(baseUrl + "/form", forms).toString());
            assertEquals("n1:hello.txt:file-body", client.post(baseUrl + "/multipart", multipartForms, files).toString());
        }
    }

    @Test
    public void cookieAndResponseCachingWork() {
        try (HttpClientV2 client = new HttpClientV2().withCookies(true)) {
            assertEquals("set", client.get(baseUrl + "/cookie-set").toString());
            assertTrue(client.get(baseUrl + "/cookie-echo").toString().contains("sid=abc"));

            HttpClientV2.Response response = client.get(baseUrl + "/large");
            String text = response.toString();
            assertEquals(text, response.toString());
            assertTrue(text.length() > 1000);

            HybridStream stream = response.toStream();
            assertTrue(stream.getLength() > 1000);
            try (InputStream in = response.responseStream()) {
                assertTrue(in.read() != -1);
            } catch (Exception e) {
                fail(e);
            }

            File file = response.toFile("http-client-v2-large.tmp");
            try {
                assertTrue(file.exists());
                assertTrue(file.length() > 1000);
            } finally {
                file.delete();
            }
        }
    }

    @Test
    public void requestBuilderMergesHeadersAndQueryString() {
        Map<String, Object> query = Collections.singletonMap("q", "hello world");
        try (HttpClientV2 client = new HttpClientV2()) {
            HttpClientV2.Request request = HttpClientV2.request(HttpMethod.GET, HttpClientV2.buildUrl(baseUrl + "/get", query))
                    .header("X-Test", "1");
            assertEquals("GET:hello world", client.execute(request).toString());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
