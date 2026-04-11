package org.rx.net.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.IOException;
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
    public void testAutoCloseable() throws IOException {
        String url = BASE_URL + "/test";
        InputStream stream;
        try (HttpClient client = new HttpClient()) {
            HttpClient.ResponseContent res = client.get(url);
            stream = res.responseStream();
            assertNotNull(stream);
            assertTrue(stream.read() != -1);
        }
        
        // After client is closed, the stream should be closed
        assertThrows(IOException.class, () -> stream.read(), "Stream should be closed after client.close()");
    }

    @Test
    public void testMultipleConsumption() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            HttpClient.ResponseContent res = client.get(url);
            String s1 = res.toString();
            assertTrue(s1.contains("ok"));
            
            // Second consumption should work because of caching in 'str' field
            String s2 = res.toString();
            assertEquals(s1, s2);
        }
    }

    @Test
    public void testConsecutiveRequests() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            HttpClient.ResponseContent res1 = client.get(url);
            HttpClient.ResponseContent res2 = client.get(url); // This closes res1.response and res1.stream
            
            // res1 should be closed now
            assertThrows(Exception.class, () -> res1.toString(), "res1 should be closed after second get()");
        }
    }

    @Test
    public void testMultipleConsumptionWithTwoMethods() {
        String url = BASE_URL + "/test";
        try (HttpClient client = new HttpClient()) {
            HttpClient.ResponseContent res = client.get(url);
            String s1 = res.toString();
            assertTrue(s1.contains("ok"));
            
            // This should fail according to my analysis because toString() closed the stream 
            // and toFile() will try to open it again when cachingStream is false.
            assertDoesNotThrow(() -> {
                res.toFile("test.tmp");
            }, "Should be able to call toFile() after toString() even if not cachingStream");
        } finally {
            new java.io.File("test.tmp").delete();
        }
    }
}
