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
            store.recordStackTrace(456L, "stack body", now);
            store.recordMetric(new DiagnosticMetric(now, "http.metric", 42D, "k=v", "inc-http", 456L));
            store.recordMetric(new DiagnosticMetric(now - 5000L, "process.cpu.percent", 12D, null, null));
            store.recordMetric(new DiagnosticMetric(now, "process.cpu.percent", 34D, null, null));
            store.recordMetric(new DiagnosticMetric(now, "system.cpu.percent", 56D, null, null));
            store.recordMetric(new DiagnosticMetric(now, "system.physical.used.percent", 58D, null, null));
            store.recordMetric(new DiagnosticMetric(now, "jvm.app.memory.used.percent", 12D, null, null));
            store.recordMetric(new DiagnosticMetric(now, "jvm.heap.used.percent", 25D, null, null));
            store.recordMetric(new DiagnosticMetric(now - 5000L, "disk.used.bytes", 262144D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now - 500L, "disk.used.bytes", 524288D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now, "disk.used.bytes", 1048576D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now, "disk.free.percent", 38D, "path=/tmp", null));
            store.recordMetric(new DiagnosticMetric(now, "net.io.inbound.bytes.per.second", 2048D, "component=http.server", null));
            store.recordThreadCpu(new ThreadCpuSample(now, 7L, "diag-thread", "RUNNABLE", 1000000L, 456L, "stack body"), "inc-http");
            store.recordThreadState(new ThreadStateSample(now, 7L, "blocked-thread", "BLOCKED", 10L, 0L, 30000L,
                    "lock", 8L, "owner-thread", 456L, "stack body"), "inc-http");
            store.recordNetIo(now, "127.0.0.1:8080", DiagnosticNetOperation.INBOUND, 2048L, 456L, "inc-http");
            store.recordIncident("inc-http", DiagnosticIncidentType.CPU_HIGH, DiagnosticLevel.DIAG, now, now,
                    "incidentId=inc-http\nsummary=directUsedBytes=48623489\n", null);
            store.recordIncident("inc-human", DiagnosticIncidentType.DIRECT_MEMORY_HIGH, DiagnosticLevel.DIAG, now + 1L, now + 1L,
                    "directUsedBytes=97.52 MB (102253591 bytes)", null);
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
                assertTrue(html.contains("Overview"));
                assertTrue(html.contains("CPU Charts"));
                assertTrue(html.contains("Memory Charts"));
                assertTrue(html.contains("Disk Charts"));
                assertTrue(html.contains("Net Charts"));
                assertTrue(html.contains("inc-http"));
                assertTrue(html.contains("http.metric"));
                assertTrue(html.contains("process.cpu.percent"));
                assertTrue(html.contains("system.physical.used.percent"));
                assertTrue(html.contains("jvm.app.memory.used.percent"));
                assertTrue(html.contains("jvm.heap.used.percent"));
                assertTrue(html.contains("net.io.inbound.bytes.per.second"));
                assertTrue(html.contains("disk.free.percent"));
                assertTrue(html.contains("1.00 MB"));
                assertTrue(html.contains("directUsedBytes=48623489 (46.37 MB)"));
                assertTrue(html.contains("directUsedBytes=97.52 MB (102253591 bytes)"));
                assertFalse(html.contains("directUsedBytes=97 (97.00 B).52 MB"));
                assertTrue(html.contains("tab-link"));
                assertTrue(html.contains("metric-chart"));
                assertTrue(html.contains("class=\"point\""));
                assertTrue(html.contains("class=\"y-label\""));
                assertTrue(html.contains("Top N"));
                assertTrue(html.contains("jvm.*"));
                assertFalse(html.contains("<th>Sum</th>"));
                assertTrue(html.contains("Thread CPU"));
                assertTrue(html.contains("name=\"capture\" value=\"thread-cpu\""));
                assertTrue(html.contains("name=\"capture\" value=\"thread-state\""));
                assertTrue(html.contains("Capture Now"));
                assertTrue(html.contains("Thread State"));
                assertFalse(html.contains("Thread Trace"));
                assertTrue(html.contains("Net I/O"));
                assertTrue(html.contains("By Endpoint"));
                assertTrue(html.contains("blocked-thread"));
                assertTrue(html.contains("127.0.0.1:8080"));
                assertTrue(html.contains("?stack=456"));
                assertFalse(html.contains("clob"));

                HttpClient.ResponseContent filtered = client.get(url + "?limit=10&metric=disk.used.bytes&from="
                        + (now - 1000L) + "&to=" + (now + 1000L));
                String filteredHtml = filtered.toString();
                assertTrue(filteredHtml.contains("disk.used.bytes"));
                assertTrue(filteredHtml.contains("512.00 KB"));
                assertTrue(filteredHtml.contains("1.00 MB"));
                assertTrue(filteredHtml.contains("samples 2"));
                assertFalse(filteredHtml.contains("256.00 KB"));
                assertFalse(filteredHtml.contains("http.metric</td>"));

                HttpClient.ResponseContent chartLimited = client.get(url + "?limit=1&from="
                        + (now - 1000L) + "&to=" + (now + 1000L));
                assertTrue(chartLimited.toString().contains("samples 2"));

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
