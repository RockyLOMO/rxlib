package org.rx.diagnostic;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class DiagnosticHttpHandlerTest {
    @Test
    public void diagnosticPageRequiresBasicAuthAndReadsH2() throws Exception {
        DiagnosticConfig config = memConfig("diag_http");
        DiagnosticConfig oldDiagnostic = RxConfig.INSTANCE.getDiagnostic();
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        H2DiagnosticStore store = new H2DiagnosticStore(config);
        HttpServer server = null;
        try {
            RxConfig.INSTANCE.setDiagnostic(config);
            RxConfig.INSTANCE.setRtoken("secret");
            store.start();
            long now = System.currentTimeMillis();
            store.recordMetric(new DiagnosticMetric(now, "http.metric", 42D, "k=v", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now - 500L, "disk.used.bytes", 524288D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now, "disk.used.bytes", 1048576D, "path=/tmp", "inc-http"));
            store.recordStackTrace(456L, "stack body", now);
            store.recordThreadCpu(new ThreadCpuSample(now, 7L, "diag-thread", "RUNNABLE", 1000000L, 456L, "stack body"), "inc-http");
            store.recordIncident("inc-http", DiagnosticIncidentType.CPU_HIGH, DiagnosticLevel.DIAG, now, now,
                    "incidentId=inc-http\nsummary=directUsedBytes=48623489\n", null);
            assertTrue(store.flush(5000L));

            int port = freePort();
            server = new HttpServer(port, false).requestDiagnostic("/diag");
            String url = "http://127.0.0.1:" + port + "/diag";
            try (HttpClient client = new HttpClient()) {
                HttpClient.ResponseContent unauthorized = client.get(url);
                assertEquals(401, unauthorized.getResponse().code());
                assertNotNull(unauthorized.getResponse().header(HttpHeaderNames.WWW_AUTHENTICATE.toString()));

                client.requestHeaders().set(HttpHeaderNames.AUTHORIZATION, basic("secret"));
                HttpClient.ResponseContent ok = client.get(url + "?limit=10");
                String html = ok.toString();
                assertEquals(200, ok.getResponse().code());
                assertTrue(html.contains("RXlib Diagnostics"));
                assertTrue(html.contains("inc-http"));
                assertTrue(html.contains("http.metric"));
                assertTrue(html.contains("1.00 MB"));
                assertTrue(html.contains("directUsedBytes=48623489 (46.37 MB)"));
                assertTrue(html.contains("metric-chart"));
                assertFalse(html.contains("clob"));

                HttpClient.ResponseContent filtered = client.get(url + "?limit=10&metric=disk.used.bytes&from="
                        + (now - 1000L) + "&to=" + (now + 1000L));
                String filteredHtml = filtered.toString();
                assertTrue(filteredHtml.contains("disk.used.bytes"));
                assertTrue(filteredHtml.contains("512.00 KB"));
                assertTrue(filteredHtml.contains("1.00 MB"));
                assertFalse(filteredHtml.contains("http.metric</td>"));

                HttpClient.ResponseContent stack = client.get(url + "?stack=456");
                assertTrue(stack.toString().contains("stack body"));
            }
        } finally {
            if (server != null) {
                server.close();
            }
            store.close();
            RxConfig.INSTANCE.setDiagnostic(oldDiagnostic);
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    private static DiagnosticConfig memConfig(String name) {
        DiagnosticConfig config = new DiagnosticConfig();
        config.setH2JdbcUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        config.setH2QueueSize(64);
        config.setH2BatchSize(16);
        config.setH2FlushIntervalMillis(50L);
        config.setH2TtlMillis(0L);
        config.setDiagnosticsMaxBytes(0L);
        config.setEvidenceMinFreeBytes(0L);
        config.setJfrMode("off");
        return config;
    }

    private static String basic(String password) {
        String token = "rxlib:" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
