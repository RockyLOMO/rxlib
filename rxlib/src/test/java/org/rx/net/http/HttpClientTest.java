package org.rx.net.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.lang.reflect.Field;
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
            for(int i=0; i<1000; i++) sb.append("Hello World ");
            res.jsonBody(sb.toString());
        });
    }

    @AfterAll
    public static void tearDown() {
        if (server != null) {
            server.close();
        }
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
        
        // After client is closed, the stream should be closed
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

                // Second consumption should work because of caching in 'str' field
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

                assertDoesNotThrow(() -> {
                    res.bodyAsFile("test.tmp");
                }, "Should be able to call bodyAsFile() after bodyAsString()");
            }
        } finally {
            new java.io.File("test.tmp").delete();
        }
    }
}
