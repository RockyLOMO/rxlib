package org.rx.diagnostic;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.exception.TraceHandler;
import org.rx.io.EntityDatabase;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.io.File;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

public class DiagnosticHttpHandlerTest {
    @Test
    public void exceptionTraceStackUsesFullWidthRow() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("modified", "2026-04-30 16:00:00");
        row.put("id", "1");
        row.put("level", "SYSTEM");
        row.put("count", "1");
        row.put("app", "rxlib");
        row.put("thread", "eventLoop");
        row.put("messagesHtml", "message");
        row.put("stackTrace", "java.lang.IllegalStateException\n\tat org.rx.Test.run(Test.java:1)");

        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("error", "");
        vars.put("empty", Boolean.FALSE);
        vars.put("rows", Collections.singletonList(row));

        String html = HttpServer.renderHtmlTemplate("rx-diagnostic-exceptions.html", vars);
        assertTrue(html.contains("<th>Modified</th><th>Id</th><th>Level</th><th>Count</th><th>App</th><th>Thread</th><th>Messages</th></tr>"));
        assertTrue(html.contains("<tr><td colspan=\"7\"><details><summary>Stack</summary><pre>java.lang.IllegalStateException"));
    }

    @Test
    public void diagnosticPageRequiresBasicAuthAndReadsH2() throws Exception {
        DiagnosticConfig config = memConfig("diag_http");
        DiagnosticConfig oldDiagnostic = RxConfig.INSTANCE.getDiagnostic();
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        H2DiagnosticStore store = new H2DiagnosticStore(config);
        HttpServer server = null;
        long methodTraceId = System.nanoTime();
        boolean methodTraceSaved = false;
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
            store.recordMetric(new DiagnosticMetric(now - 5000L, "disk.used.bytes", 262144D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now - 500L, "disk.used.bytes", 524288D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now, "disk.used.bytes", 1048576D, "path=/tmp", "inc-http"));
            store.recordMetric(new DiagnosticMetric(now, "disk.free.percent", 38D, "path=/tmp", null));
            store.recordThreadCpu(new ThreadCpuSample(now, 7L, "diag-thread", "RUNNABLE", 1000000L, 456L, "stack body"), "inc-http");
            store.recordThreadState(new ThreadStateSample(now, 7L, "blocked-thread", "BLOCKED", 10L, 0L, 30000L,
                    "lock", 8L, "owner-thread", 456L, "stack body"), "inc-http");
            store.recordNetIo(now, "127.0.0.1:8080", DiagnosticNetOperation.INBOUND, 2048L, 456L, "inc-http");
            store.recordIncident("inc-http", DiagnosticIncidentType.CPU_HIGH, DiagnosticLevel.DIAG, now, now,
                    "incidentId=inc-http\nsummary=directUsedBytes=48623489\n", null);
            store.recordIncident("inc-human", DiagnosticIncidentType.DIRECT_MEMORY_HIGH, DiagnosticLevel.DIAG, now + 1L, now + 1L,
                    "directUsedBytes=97.52 MB (102253591 bytes)", null);
            assertTrue(store.flush(5000L));
            EntityDatabase.DEFAULT.createMapping(TraceHandler.MethodEntity.class);
            TraceHandler.MethodEntity methodTrace = new TraceHandler.MethodEntity();
            methodTrace.setId(methodTraceId);
            methodTrace.setMethodName("rxlib.test.method.foo(1)");
            methodTrace.setParameters("[\"arg\"]");
            methodTrace.setReturnValue("\"ok\"");
            methodTrace.setElapsedMicros(123456L);
            methodTrace.setOccurCount(3);
            methodTrace.setAppName("diag-test");
            methodTrace.setThreadName("diag-thread");
            methodTrace.setModifyTime(new Date(now));
            EntityDatabase.DEFAULT.save(methodTrace, true);
            methodTraceSaved = true;

            int port = freePort();
            server = new HttpServer(port, false).requestDiagnostic("/diag");
            String url = "http://127.0.0.1:" + port + "/diag";
            try (HttpClient client = new HttpClient()) {
                HttpClient.Response unauthorized = client.get(url);
                assertEquals(401, unauthorized.code());
                assertNotNull(unauthorized.header(HttpHeaderNames.WWW_AUTHENTICATE.toString()));

                client.requestHeaders().set(HttpHeaderNames.AUTHORIZATION, basic("secret"));
                HttpClient.Response ok = client.get(url + "?limit=10");
                String html = ok.bodyAsString();
                assertEquals(200, ok.code());
                assertTrue(html.contains("RXlib Diagnostics"));
                assertTrue(html.contains("Overview"));
                assertTrue(html.contains("Runtime State"));
                assertTrue(html.contains("Socks Extensions"));
                assertTrue(html.contains("omega.rrpClient.registered"));
                assertTrue(html.contains("omegax.sshServer.registered"));
                assertTrue(html.contains("name=\"action\" value=\"omega\""));
                assertTrue(html.contains("name=\"omegaConfig\""));
                assertTrue(html.contains("name=\"action\" value=\"omegax\""));
                assertTrue(html.contains("name=\"omegaxPort\""));
                assertTrue(html.contains("Input Arguments"));
                assertTrue(html.contains("System Properties"));
                assertTrue(html.contains("System Environment"));
                assertTrue(html.contains("Request Headers"));
                assertTrue(html.contains("&quot;rtoken&quot;:&quot;***&quot;"));
                assertFalse(html.contains("&quot;rtoken&quot;:&quot;secret&quot;"));
                assertTrue(html.contains("CPU Charts"));
                assertTrue(html.contains("Disk Charts"));
                assertTrue(html.contains("ThreadMXBean"));
                assertTrue(html.contains("name=\"action\" value=\"thread-mx\""));
                assertTrue(html.contains("Exception Traces"));
                assertTrue(html.contains("name=\"exceptionLevel\""));
                assertTrue(html.contains("name=\"exceptionKeyword\""));
                assertTrue(html.contains("name=\"exceptionNewest\""));
                assertTrue(html.contains("Method Traces"));
                assertTrue(html.contains("name=\"methodNamePrefix\""));
                assertTrue(html.contains("name=\"methodOccurMost\""));
                assertTrue(html.contains("Rx Metrics"));
                assertTrue(html.contains("inc-http"));
                assertTrue(html.contains("http.metric"));
                assertTrue(html.contains("process.cpu.percent"));
                assertTrue(html.contains("disk.free.percent"));
                assertTrue(html.contains("1.00 MB"));
                assertTrue(html.contains("directUsedBytes=48623489 (46.37 MB)"));
                assertTrue(html.contains("directUsedBytes=97.52 MB (102253591 bytes)"));
                assertFalse(html.contains("directUsedBytes=97 (97.00 B).52 MB"));
                assertTrue(html.contains("tab-link"));
                assertTrue(html.contains("document.activeElement.blur()"));
                assertFalse(html.contains("backdrop-filter"));
                assertTrue(html.contains("metric-chart"));
                assertTrue(html.contains("class=\"point\""));
                assertTrue(html.contains("y-label"));
                assertTrue(html.contains("Top N"));
                assertTrue(html.contains("Thread State"));
                assertTrue(html.contains("Net I/O"));
                assertTrue(html.contains("net.* Charts"));
                assertTrue(html.contains("diagnostic.netIoBytesPerSecondThreshold"));
                assertTrue(html.contains("Tools"));
                assertTrue(html.contains("name=\"toolHost\""));
                assertTrue(html.contains("name=\"toolCmd\""));
                assertTrue(html.contains("Bean/Class Read Write"));
                assertTrue(html.contains("Reflect Invoke"));
                assertTrue(html.contains("VM Options"));
                assertTrue(html.contains("UnlockCommercialFeatures"));
                assertTrue(html.contains("name=\"vmOptionName\""));
                assertTrue(html.contains("Writable Options"));
                assertTrue(html.contains("CPU available processors:"));
                assertTrue(html.contains("diagnostic.cpuThresholdPercent"));
                assertTrue(html.contains("diagnostic.threadBlockedThresholdCount"));
                assertTrue(html.contains("diagnostic.diskFreePercentThreshold"));
                assertTrue(html.contains("File.listRoots() Disk Usage"));
                assertTrue(html.contains("blocked-thread"));
                assertTrue(html.contains("127.0.0.1:8080"));
                assertTrue(html.contains("?stack=456"));
                assertFalse(html.contains("clob"));

                HttpClient.Response filtered = client.get(url + "?limit=10&metric=disk.used.bytes&from="
                        + (now - 1000L) + "&to=" + (now + 1000L));
                String filteredHtml = filtered.bodyAsString();
                assertTrue(filteredHtml.contains("disk.used.bytes"));
                assertTrue(filteredHtml.contains("512.00 KB"));
                assertTrue(filteredHtml.contains("1.00 MB"));
                assertTrue(filteredHtml.contains("samples 2"));
                assertFalse(filteredHtml.contains("256.00 KB"));
                assertFalse(filteredHtml.contains("http.metric</td>"));

                HttpClient.Response chartLimited = client.get(url + "?limit=1&from="
                        + (now - 1000L) + "&to=" + (now + 1000L));
                assertTrue(chartLimited.bodyAsString().contains("samples 2"));

                HttpClient.Response stack = client.get(url + "?stack=456");
                assertTrue(stack.bodyAsString().contains("stack body"));

                HttpClient.Response exceptionFiltered = client.get(url + "?take=10&startTime="
                        + (now - 1000L) + "&endTime=" + (now + 1000L)
                        + "&level=SYSTEM&keyword=IllegalState&newest=true#exceptions");
                String exceptionFilteredHtml = exceptionFiltered.bodyAsString();
                assertTrue(exceptionFilteredHtml.contains("value=\"SYSTEM\" selected"));
                assertTrue(exceptionFilteredHtml.contains("value=\"IllegalState\""));
                assertTrue(exceptionFilteredHtml.contains("value=\"true\" selected"));
                assertTrue(exceptionFilteredHtml.contains("latest 10 rows per section"));
                assertTrue(exceptionFilteredHtml.contains("data-context-tab=\"exceptions\""));

                HttpClient.Response methodFiltered = client.get(url + "?limit=10&methodNamePrefix=rxlib.test.method&methodOccurMost=true#method-traces");
                String methodFilteredHtml = methodFiltered.bodyAsString();
                assertTrue(methodFilteredHtml.contains("rxlib.test.method.foo(1)"));
                assertTrue(methodFilteredHtml.contains("Most Occurred"));
                assertTrue(methodFilteredHtml.contains("data-context-tab=\"method-traces\""));

                HttpClient.Response dns = client.get(url + "?action=5&host=localhost");
                String dnsHtml = dns.bodyAsString();
                assertTrue(dnsHtml.contains("DNS Results"));
                assertTrue(dnsHtml.contains("value=\"localhost\""));

                String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator
                        + (File.separatorChar == '\\' ? "java.exe" : "java");
                String cmd = "\"" + javaExe + "\" -version";
                HttpClient.Response exec = client.get(url + "?action=13&cmd="
                        + URLEncoder.encode(cmd, StandardCharsets.UTF_8.name()));
                String execHtml = exec.bodyAsString();
                assertTrue(execHtml.contains("Command execution completed"));
                assertTrue(execHtml.toLowerCase().contains("version"));

                HttpClient.Response bean = client.get(url + "?action=3&member=rtoken");
                String beanHtml = bean.bodyAsString();
                assertTrue(beanHtml.contains("Bean/Class read write completed"));
                assertTrue(beanHtml.contains("secret"));

                HttpClient.Response invoke = client.get(url + "?action=12&expr=org.rx.core.Strings.trimVarExpressionName&args="
                        + URLEncoder.encode("[\"${abc}\"]", StandardCharsets.UTF_8.name()));
                String invokeHtml = invoke.bodyAsString();
                assertTrue(invokeHtml.contains("Reflect invoke completed"));
                assertTrue(invokeHtml.contains("abc"));

                ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
                Boolean oldCpu = threadMx.isThreadCpuTimeSupported()
                        ? Boolean.valueOf(threadMx.isThreadCpuTimeEnabled()) : null;
                Boolean oldContention = threadMx.isThreadContentionMonitoringSupported()
                        ? Boolean.valueOf(threadMx.isThreadContentionMonitoringEnabled()) : null;
                try {
                    HttpClient.Response threadMxOff = client.get(url + "?limit=10&action=thread-mx&enabled=false");
                    assertEquals(200, threadMxOff.code());
                    assertTrue(threadMxOff.bodyAsString().contains("ThreadMXBean updated"));
                } finally {
                    if (oldCpu != null) {
                        threadMx.setThreadCpuTimeEnabled(oldCpu.booleanValue());
                    }
                    if (oldContention != null) {
                        threadMx.setThreadContentionMonitoringEnabled(oldContention.booleanValue());
                    }
                }
            }
        } finally {
            if (methodTraceSaved) {
                EntityDatabase.DEFAULT.deleteById(TraceHandler.MethodEntity.class, methodTraceId);
            }
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
