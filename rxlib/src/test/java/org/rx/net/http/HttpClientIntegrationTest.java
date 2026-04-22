package org.rx.net.http;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.net.Sockets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientIntegrationTest {
    private static HttpServer server;
    private static String baseUrl;
    private static final Set<Integer> remotePorts = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static volatile CountDownLatch slowStarted;

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
        server.requestMapping("/gzip", (req, res) -> {
            res.getHeaders().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
            res.setContentType(ServerResponse.TEXT_HTML.toString());
            res.setContent(Unpooled.wrappedBuffer(gzip("gzip-ok")));
        });
        server.requestMapping("/remote-port", (req, res) -> {
            int portValue = req.getRemoteEndpoint().getPort();
            remotePorts.add(portValue);
            res.htmlBody(String.valueOf(portValue));
        });
        server.requestAsync("/slow", (req, res) -> {
            CountDownLatch latch = slowStarted;
            if (latch != null) {
                latch.countDown();
            }
            Thread.sleep(300L);
            res.htmlBody("slow");
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
        try (HttpClient client = new HttpClient()) {
            HttpClient.Response response = client.get(baseUrl + "/get?q=ok");
            assertEquals(200, response.getStatusCode());
            assertEquals("GET:ok", response.toString());

            HttpClient.Response head = client.head(baseUrl + "/head");
            assertEquals(200, head.getStatusCode());
            assertEquals(2, client.getMetrics().requests());
            assertEquals(2, client.getMetrics().success());
            assertTrue(client.getMetrics().maxLatencyNanos() > 0);
            assertTrue(client.getMetrics().usedDirectMemory() >= 0);
        }
    }

    @Test
    public void jsonBodyWorksForPostPutPatchDelete() {
        Map<String, Object> json = new HashMap<>();
        json.put("hello", "world");

        try (HttpClient client = new HttpClient()) {
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

        try (HttpClient client = new HttpClient()) {
            assertEquals("POST:1:two words", client.post(baseUrl + "/form", forms).toString());
            assertEquals("n1:hello.txt:file-body", client.post(baseUrl + "/multipart", multipartForms, files).toString());
        }
    }

    @Test
    public void cookieAndResponseCachingWork() {
        try (HttpClient client = new HttpClient()) {
            assertEquals("set", client.get(baseUrl + "/cookie-set").toString());
            assertTrue(client.get(baseUrl + "/cookie-echo").toString().contains("sid=abc"));

            try (HttpClient.Response response = client.get(baseUrl + "/large")) {
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
    }

    @Test
    public void requestBuilderMergesHeadersAndQueryString() {
        Map<String, Object> query = Collections.singletonMap("q", "hello world");
        try (HttpClient client = new HttpClient()) {
            HttpClient.Request request = HttpClient.request(HttpMethod.GET, HttpClient.buildUrl(baseUrl + "/get", query))
                    .header("X-Test", "1");
            assertEquals("GET:hello world", client.execute(request).toString());
        }
    }

    @Test
    public void syncApiRejectsEventLoopThread() throws Exception {
        try (HttpClient client = new HttpClient()) {
            Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true).next()
                    .submit(() -> assertThrows(IllegalStateException.class, () -> client.get(baseUrl + "/get?q=ok")))
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void gzipResponseIsDecoded() {
        try (HttpClient client = new HttpClient()) {
            assertEquals("gzip-ok", client.get(baseUrl + "/gzip").toString());
        }
    }

    @Test
    public void keepAliveReusesFixedPoolChannel() {
        remotePorts.clear();
        try (HttpClient client = new HttpClient(new HttpClientConfig().setMaxConnectionsPerHost(1))) {
            String first = client.get(baseUrl + "/remote-port").toString();
            String second = client.get(baseUrl + "/remote-port").toString();
            assertEquals(first, second);
            assertEquals(1, remotePorts.size());
        }
    }

    @Test
    public void requestTimeoutCompletesExceptionally() {
        try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(1000))) {
            HttpClient.Request request = HttpClient.request(HttpMethod.GET, baseUrl + "/slow")
                    .timeoutMillis(100);
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.executeAsync(request).get(2, TimeUnit.SECONDS));
            assertTrue(hasCause(error, TimeoutException.class), error.toString());
            assertEquals(1, client.getMetrics().timeout());
        }
    }

    @Test
    public void fixedPoolLimitsConcurrentAcquire() throws Exception {
        slowStarted = new CountDownLatch(1);
        try (HttpClient client = new HttpClient(new HttpClientConfig()
                .setConnectTimeoutMillis(80)
                .setReadWriteTimeoutMillis(1000)
                .setMaxConnectionsPerHost(1))) {
            CompletableFuture<HttpClient.ResponseContent> first = client.executeAsync(
                    HttpClient.request(HttpMethod.GET, baseUrl + "/slow").timeoutMillis(1000));
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<HttpClient.ResponseContent> queued = client.executeAsync(
                    HttpClient.request(HttpMethod.GET, baseUrl + "/get?q=queued").timeoutMillis(1000));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> queued.get(2, TimeUnit.SECONDS));
            assertTrue(hasCause(error, TimeoutException.class), error.toString());
            assertEquals(200, first.get(2, TimeUnit.SECONDS).getStatusCode());
            assertTrue(client.getMetrics().failed() >= 1);
        } finally {
            slowStarted = null;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        try {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        } finally {
            gzip.close();
        }
        return out.toByteArray();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (int i = 0; error != null && i < 16; i++) {
            if (type.isInstance(error)) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }
}
