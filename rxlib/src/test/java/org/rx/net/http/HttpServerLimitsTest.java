package org.rx.net.http;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerLimitsTest {
    private static HttpServerConfig config() {
        return new HttpServerConfig().setBindAddress(new InetSocketAddress("127.0.0.1", 0));
    }

    private static Socket connect(HttpServer server) throws Exception {
        Socket socket = new Socket();
        socket.connect((InetSocketAddress) server.serverChannels.get(0).localAddress(), 3000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static void send(Socket socket, String data) throws Exception {
        socket.getOutputStream().write(data.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readClosed(Socket socket) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        InputStream input = socket.getInputStream();
        int count;
        while ((count = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void status(String response, int status) {
        assertTrue(response.startsWith("HTTP/1.1 " + status + " ")
                || response.startsWith("HTTP/1.0 " + status + " "), response);
    }

    private static String request(HttpServer server, String request) throws Exception {
        try (Socket socket = connect(server)) {
            send(socket, request);
            return readClosed(socket);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    private static void noRequests(HttpServer server) throws Exception {
        await(() -> server.getMetrics().activeRequests() == 0);
    }

    @Test
    void knownLengthAndChunkedBodiesAreBounded() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setMaxRequestBodyBytes(4))) {
            server.requestMapping("/test", (req, res) -> fail("Oversize body reached handler"));
            status(request(server, "POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n"), 413);
            status(request(server, "POST /test HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"), 413);
            noRequests(server);
            assertTrue(server.getMetrics().rejected() >= 2);
        }
    }

    private static String multipart(String name, boolean file, String value) {
        return "--boundary\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (file ? "; filename=\"file.txt\"\r\nContent-Type: text/plain" : "")
                + "\r\n\r\n" + value + "\r\n";
    }

    private static String multipartRequest(String body) {
        return "POST /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "Content-Type: multipart/form-data; boundary=boundary\r\nContent-Length: "
                + body.length() + "\r\n\r\n" + body;
    }

    @Test
    void multipartFileFieldSizeAndFieldCountAreBounded() throws Exception {
        try (HttpServer server = new HttpServer(0, false,
                config().setMaxFileBytes(4).setMaxFormFieldBytes(4).setMaxFormFields(1))) {
            server.requestMapping("/test", (req, res) -> fail("Oversize form reached handler"));
            status(request(server, multipartRequest(multipart("file", true, "hello") + "--boundary--\r\n")), 413);
            status(request(server, multipartRequest(multipart("field", false, "hello") + "--boundary--\r\n")), 413);
            status(request(server, multipartRequest(multipart("one", false, "a")
                    + multipart("two", false, "b") + "--boundary--\r\n")), 413);
            noRequests(server);
        }
    }

    @Test
    void totalMultipartAndUrlEncodedFieldsAreBounded() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setMaxMultipartBytes(32))) {
            server.requestMapping("/test", (req, res) -> fail("Oversize multipart reached handler"));
            status(request(server, multipartRequest(multipart("field", false, "value") + "--boundary--\r\n")), 413);
            noRequests(server);
        }
        try (HttpServer server = new HttpServer(0, false, config().setMaxFormFieldBytes(4).setMaxFormFields(1))) {
            server.requestMapping("/test", (req, res) -> fail("Oversize form reached handler"));
            String headers = "POST /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ";
            status(request(server, headers + "7\r\n\r\na=hello"), 413);
            status(request(server, headers + "7\r\n\r\na=1&b=2"), 413);
            noRequests(server);
        }
    }

    @Test
    void earlyNotFoundAndMethodFailureCloseUnreadBodies() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config())) {
            server.requestMapping("/test", new HttpServer.Handler() {
                @Override
                public HttpMethod[] method() {
                    return new HttpMethod[]{HttpMethod.GET};
                }
                @Override
                public void handle(ServerRequest req, ServerResponse res) {
                    fail("Wrong method reached handler");
                }
            });
            // Body is deliberately not sent: waiting to drain it would leave the socket open.
            status(request(server, "POST /missing HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n"), 404);
            status(request(server, "POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n"), 405);
            noRequests(server);
        }
    }

    @Test
    void uriAndHeaderLimitsReturnSpecificStatus() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setMaxUriLength(32).setMaxHeaderBytes(128))) {
            server.requestMapping("/test", (req, res) -> res.htmlBody("ok"));
            status(request(server, "GET /" + repeat('a', 64) + " HTTP/1.1\r\nHost: localhost\r\n\r\n"), 414);
            status(request(server, "GET /test HTTP/1.1\r\nHost: localhost\r\nX-Large: " + repeat('a', 256) + "\r\n\r\n"), 431);
            noRequests(server);
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    @Test
    void incompleteHeadersAndBodyTimeOut() throws Exception {
        try (HttpServer server = new HttpServer(0, false,
                config().setHeaderTimeoutMillis(400).setBodyTimeoutMillis(400))) {
            server.requestMapping("/test", (req, res) -> fail("Incomplete request reached handler"));
            status(request(server, "GET /test HTTP/1.1\r\nHost: local"), 408);
            status(request(server, "POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\na"), 408);
            noRequests(server);
            assertTrue(server.getMetrics().timedOut() >= 2);
        }
    }

    @Test
    void keepAliveIdleTimesOutAfterSuccessfulRequest() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setKeepAliveIdleMillis(400))) {
            server.requestMapping("/test", (req, res) -> res.htmlBody("ok"));
            try (Socket socket = connect(server)) {
                send(socket, "GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String responses = readClosed(socket);
                status(responses, 200);
                assertFalse(responses.contains("HTTP/1.1 408 "), responses);
            }
            noRequests(server);
            await(() -> server.getMetrics().activeConnections() == 0);
        }
    }

    @Test
    void concurrentConnectionLimitRejectsAdditionalConnection() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setMaxConnections(1))) {
            try (Socket first = connect(server)) {
                await(() -> server.getMetrics().activeConnections() == 1);
                try (Socket second = connect(server)) {
                    assertEquals("", readClosed(second));
                }
                assertEquals(1, server.getMetrics().activeConnections());
            }
            await(() -> server.getMetrics().activeConnections() == 0);
        }
    }

    @Test
    void concurrentUploadsAreBoundedAndReleaseOnDisconnect() throws Exception {
        try (HttpServer server = new HttpServer(0, false, config().setMaxConcurrentUploads(1))) {
            server.requestMapping("/test", (req, res) -> res.htmlBody("ok"));
            try (Socket first = connect(server)) {
                send(first, "POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=boundary\r\nTransfer-Encoding: chunked\r\n\r\n");
                await(() -> server.getMetrics().activeRequests() == 1);
                status(request(server, multipartRequest(multipart("field", false, "ok") + "--boundary--\r\n")), 503);
            }
            noRequests(server);
            status(request(server, multipartRequest(multipart("field", false, "ok") + "--boundary--\r\n")), 200);
            noRequests(server);
        }
    }

    @Test
    void concurrentAsyncHandlersAreBoundedAndPermitRecovers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (HttpServer server = new HttpServer(0, false, config().setMaxAsyncRequests(1))) {
            server.requestAsync("/test", (req, res) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                res.htmlBody("ok");
            });
            try (Socket first = connect(server)) {
                send(first, "GET /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try {
                    status(request(server, "GET /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"), 503);
                } finally {
                    release.countDown();
                }
                status(readClosed(first), 200);
            } finally {
                release.countDown();
            }
            noRequests(server);
            status(request(server, "GET /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"), 200);
            noRequests(server);
        }
    }

    @Test
    void configurationRejectsInvalidLimitsAndCopiesSettings() {
        HttpServerConfig original = config().setMaxRequestBodyBytes(4).setMaxAsyncRequests(2);
        HttpServerConfig copy = new HttpServerConfig(original);
        original.setMaxRequestBodyBytes(8);
        assertEquals(4, copy.getMaxRequestBodyBytes());
        assertEquals(2, copy.getMaxAsyncRequests());
        assertEquals(original.getBindAddress(), copy.getBindAddress());
        assertSame(original.getBusinessExecutor(), copy.getBusinessExecutor());
        assertThrows(IllegalArgumentException.class, () -> original.setMaxRequestBodyBytes(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxRequestBodyBytes(1L + Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxMultipartBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxFileBytes(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxFormFields(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxFormFieldBytes(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxUriLength(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxHeaderBytes(0));
        assertThrows(IllegalArgumentException.class, () -> original.setHeaderTimeoutMillis(0));
        assertThrows(IllegalArgumentException.class, () -> original.setBodyTimeoutMillis(0));
        assertThrows(IllegalArgumentException.class, () -> original.setKeepAliveIdleMillis(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxConnections(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxConcurrentUploads(0));
        assertThrows(IllegalArgumentException.class, () -> original.setMaxAsyncRequests(0));
        assertThrows(NullPointerException.class, () -> original.setBusinessExecutor(null));
        assertNull(copy.setBindAddress(null).getBindAddress());
    }
}


