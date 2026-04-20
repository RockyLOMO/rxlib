package org.rx.net.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpServerBlockingTest {
    private static HttpServer server;
    private static final int PORT = 18081;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final AtomicReference<String> normalThread = new AtomicReference<>();
    private static final AtomicReference<String> blockingThread = new AtomicReference<>();

    @BeforeAll
    public static void setup() {
        server = new HttpServer(PORT, false);
        server.requestMapping("/normal", (req, res) -> {
            normalThread.set(Thread.currentThread().getName());
            res.jsonBody("ok");
        });
        server.requestBlocking("/blocking", (req, res) -> {
            blockingThread.set(Thread.currentThread().getName());
            Thread.sleep(50L);
            res.jsonBody("ok");
        });
    }

    @AfterAll
    public static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void blockingHandler_offloadsFromEventLoop() {
        try (HttpClient client = new HttpClient()) {
            assertTrue(client.get(BASE_URL + "/normal").toString().contains("ok"));
            HttpClient.ResponseContent response = client.get(BASE_URL + "/blocking");
            assertTrue(response.toString().contains("ok"));
            assertEquals("1", response.responseHeaders().get(HttpServer.BLOCKING_HANDLER_HEADER));
        }

        assertNotNull(normalThread.get());
        assertNotNull(blockingThread.get());
        assertTrue(normalThread.get().contains("nioEventLoopGroup"), normalThread.get());
        assertFalse(blockingThread.get().contains("nioEventLoopGroup"), blockingThread.get());
        assertNotEquals(normalThread.get(), blockingThread.get());
    }

    @Test
    public void blockingHandler_canServeConsecutiveRequests() {
        try (HttpClient client = new HttpClient()) {
            assertTrue(client.get(BASE_URL + "/blocking").toString().contains("ok"));
            assertTrue(client.get(BASE_URL + "/normal").toString().contains("ok"));
        }
    }

    @Test
    public void htmlTemplate_resolvesResourceVariables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Template OK");
        vars.put("body", "<section>body</section>");
        String html = HttpServer.renderHtmlTemplate("rx-diagnostic.html", vars);
        assertTrue(html.contains("<title>Template OK</title>"));
        assertTrue(html.contains("<section>body</section>"));
    }
}
