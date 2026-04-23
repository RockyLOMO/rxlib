package org.springframework.service;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.diagnostic.H2DiagnosticStore;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class HandlerUtilTest {
    @Test
    public void aroundServesDiagnosticPageOnSpringWebPath() throws Throwable {
        HandlerUtil handlerUtil = new HandlerUtil();
        DiagnosticConfig oldDiagnostic = RxConfig.INSTANCE.getDiagnostic();
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        DiagnosticConfig config = memConfig("spring_diag");
        H2DiagnosticStore store = new H2DiagnosticStore(config);
        try {
            RxConfig.INSTANCE.setDiagnostic(config);
            RxConfig.INSTANCE.setRtoken("secret");
            store.start();

            MockHttpServletRequest unauthorizedRequest = new MockHttpServletRequest("GET", "/rdiag");
            MockHttpServletResponse unauthorizedResponse = new MockHttpServletResponse();
            assertFalse(handlerUtil.around(unauthorizedRequest, unauthorizedResponse));
            assertEquals(401, unauthorizedResponse.getStatus());
            assertNotNull(unauthorizedResponse.getHeader(HttpHeaderNames.WWW_AUTHENTICATE.toString()));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rdiag");
            request.addHeader(HttpHeaderNames.AUTHORIZATION.toString(), basic("secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(handlerUtil.around(request, response));
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("RXlib Diagnostics"));
            assertTrue(response.getContentAsString().contains("Runtime State"));
        } finally {
            store.close();
            RxConfig.INSTANCE.setDiagnostic(oldDiagnostic);
            RxConfig.INSTANCE.setRtoken(oldRtoken);
        }
    }

    @Test
    public void aroundKeepsLegacyCaseOneOnly() throws Throwable {
        HandlerUtil handlerUtil = new HandlerUtil();
        String oldRtoken = RxConfig.INSTANCE.getRtoken();
        try {
            RxConfig.INSTANCE.setRtoken("secret");
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
            request.addHeader("rtoken", "secret");
            request.setParameter("_p", "{\"x\":12}");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(handlerUtil.around(request, response));
            assertEquals("0", response.getContentAsString());
        } finally {
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
}
