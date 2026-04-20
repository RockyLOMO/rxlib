package org.rx.net.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpServerBlockingTest {
    private static HttpServer server;
    private static final int PORT = 18081;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final AtomicReference<String> normalThread = new AtomicReference<>();
    private static final AtomicReference<String> asyncThread = new AtomicReference<>();

    @BeforeAll
    public static void setup() {
        server = new HttpServer(PORT, false);
        server.requestMapping("/normal", (req, res) -> {
            normalThread.set(Thread.currentThread().getName());
            res.jsonBody("ok");
        });
        server.requestAsync("/async", (req, res) -> {
            asyncThread.set(Thread.currentThread().getName());
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
    public void asyncHandler_offloadsFromEventLoop() {
        try (HttpClient client = new HttpClient()) {
            assertTrue(client.get(BASE_URL + "/normal").toString().contains("ok"));
            HttpClient.ResponseContent response = client.get(BASE_URL + "/async");
            assertTrue(response.toString().contains("ok"));
            assertEquals("1", response.responseHeaders().get(HttpServer.ASYNC_HANDLER_HEADER));
        }

        assertNotNull(normalThread.get());
        assertNotNull(asyncThread.get());
        assertTrue(normalThread.get().contains("nioEventLoopGroup"), normalThread.get());
        assertFalse(asyncThread.get().contains("nioEventLoopGroup"), asyncThread.get());
        assertNotEquals(normalThread.get(), asyncThread.get());
    }

    @Test
    public void asyncHandler_canServeConsecutiveRequests() {
        try (HttpClient client = new HttpClient()) {
            assertTrue(client.get(BASE_URL + "/async").toString().contains("ok"));
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

    @Test
    public void tlsWithoutCertificate_servesLocalhostRequest() throws Exception {
        RxConfig.HttpConfig http = RxConfig.INSTANCE.getNet().getHttp();
        String oldCertificatePath = http.getServerCertificatePath();
        String oldCertificatePassword = http.getServerCertificatePassword();
        HttpServer tlsServer = null;
        try {
            http.setServerCertificatePath(null);
            http.setServerCertificatePassword(null);

            int port = freePort();
            tlsServer = new HttpServer(port, true);
            tlsServer.requestMapping("/tls-ok", (req, res) -> res.htmlBody("ok"));

            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://localhost:" + port + "/tls-ok").openConnection();
            connection.setSSLSocketFactory(trustAllSocketFactory());
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            assertEquals(200, connection.getResponseCode());
            assertEquals("ok", readBody(connection.getInputStream()));
        } finally {
            if (tlsServer != null) {
                tlsServer.close();
            }
            http.setServerCertificatePath(oldCertificatePath);
            http.setServerCertificatePassword(oldCertificatePassword);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SSLSocketFactory trustAllSocketFactory() throws Exception {
        TrustManager[] trustManagers = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context.getSocketFactory();
    }

    private static String readBody(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16);
            byte[] buf = new byte[128];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
