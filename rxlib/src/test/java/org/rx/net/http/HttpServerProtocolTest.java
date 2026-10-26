package org.rx.net.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerProtocolTest {
    private static HttpServer server(HttpServerConfig config) {
        return new HttpServer(0, false, config.setBindAddress(new InetSocketAddress("127.0.0.1", 0)));
    }

    private static final class Reply {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class Wire implements AutoCloseable {
        final Socket socket = new Socket();
        final InputStream input;

        Wire(HttpServer server) throws IOException {
            socket.connect(server.serverChannels.get(0).localAddress(), 5000);
            socket.setSoTimeout(5000);
            input = socket.getInputStream();
        }

        void send(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        Reply request(String method, String path, boolean head) throws IOException {
            send(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            return read(head);
        }

        Reply read(boolean head) throws IOException {
            Reply reply = new Reply();
            String status = line(input);
            assertTrue(status.startsWith("HTTP/1.1 "), status);
            reply.status = Integer.parseInt(status.split(" ", 3)[1]);
            String header;
            while (!(header = line(input)).isEmpty()) {
                int colon = header.indexOf(':');
                assertTrue(colon > 0, header);
                reply.headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            if (head || reply.status < 200 || reply.status == 204 || reply.status == 304) {
                reply.body = new byte[0];
            } else if ("chunked".equalsIgnoreCase(reply.headers.get("transfer-encoding"))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (;;) {
                    String size = line(input).split(";", 2)[0];
                    int count = Integer.parseInt(size, 16);
                    if (count == 0) {
                        while (!line(input).isEmpty()) { /* trailers */ }
                        break;
                    }
                    bytes.write(exact(input, count));
                    assertEquals("", line(input));
                }
                reply.body = bytes.toByteArray();
            } else {
                assertTrue(reply.headers.containsKey("content-length"), "Response requires explicit framing");
                reply.body = exact(input, Integer.parseInt(reply.headers.get("content-length")));
            }
            return reply;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') {
                byte[] line = bytes.toByteArray();
                assertTrue(line.length > 0 && line[line.length - 1] == '\r', "HTTP line must end in CRLF");
                return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
            }
            assertTrue(bytes.size() < 65536, "Unexpectedly long HTTP line");
            bytes.write(value);
        }
        throw new EOFException("EOF while reading HTTP line");
    }

    private static byte[] exact(InputStream input, int length) throws IOException {
        assertTrue(length >= 0 && length <= 1024 * 1024, "Unexpected response size");
        byte[] bytes = new byte[length];
        for (int offset = 0; offset < length;) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("Incomplete HTTP body");
            offset += count;
        }
        return bytes;
    }

    @Test
    void redirectHasFoundStatusLocationAndEmptyBody() throws Exception {
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/redirect", (req, res) -> {
                res.htmlBody("discard me");
                res.redirect("/next");
            });
            try (Wire wire = new Wire(server)) {
                Reply reply = wire.request("GET", "/redirect", false);
                assertEquals(302, reply.status);
                assertEquals("/next", reply.headers.get("location"));
                assertEquals(0, reply.body.length);
            }
        }
    }

    @Test
    void headAndHeadHandlerFailureAllowNextGetWithoutStrayBody() throws Exception {
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/body", (req, res) -> res.htmlBody("hello"));
            server.requestMapping("/failure", (req, res) -> {
                res.htmlBody("discard");
                throw new IllegalStateException("expected failure");
            });
            try (Wire wire = new Wire(server)) {
                Reply head = wire.request("HEAD", "/body", true);
                assertEquals(200, head.status);
                assertEquals("5", head.headers.get("content-length"));
                assertEquals("hello", wire.request("GET", "/body", false).text());
                assertEquals(500, wire.request("HEAD", "/failure", true).status);
                assertEquals("hello", wire.request("GET", "/body", false).text());
            }
        }
    }

    @Test
    void headUnknownRouteSendsNoBodyBeforeClosing() throws Exception {
        try (HttpServer server = server(new HttpServerConfig()); Wire wire = new Wire(server)) {
            assertEquals(404, wire.request("HEAD", "/missing", true).status);
            assertEquals(-1, wire.input.read());
        }
    }

    @Test
    void noContentStatusesDiscardBodyAndPreserveLegalMetadata() throws Exception {
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/body", (req, res) -> res.htmlBody("ok"));
            for (int status : new int[]{204, 205, 304}) {
                server.requestMapping("/status/" + status, (req, res) -> {
                    res.setStatus(HttpResponseStatus.valueOf(status));
                    res.htmlBody("discard");
                    res.getHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 123);
                });
            }
            try (Wire wire = new Wire(server)) {
                for (int status : new int[]{204, 205, 304}) {
                    Reply reply = wire.request("GET", "/status/" + status, false);
                    assertEquals(status, reply.status);
                    assertEquals(0, reply.body.length);
                    assertFalse(reply.headers.containsKey("transfer-encoding"));
                    if (status == 204) assertFalse(reply.headers.containsKey("content-length"));
                    if (status == 205) assertEquals("0", reply.headers.get("content-length"));
                    if (status == 304) assertEquals("123", reply.headers.get("content-length"));
                    assertEquals("ok", wire.request("GET", "/body", false).text());
                }
            }
        }
    }

    @Test
    void routesNormalizeTrailingSlashesDecodePathsAndKeepCase() throws Exception {
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/Case name/", (req, res) -> res.htmlBody("upper"));
            server.requestMapping("/case name", (req, res) -> res.htmlBody("lower"));
            server.requestMapping("/", (req, res) -> res.htmlBody("root"));
            try (Wire wire = new Wire(server)) {
                assertEquals("upper", wire.request("GET", "/Case%20name/?ignored=yes", false).text());
                assertEquals("upper", wire.request("GET", "/Case%20name", false).text());
                assertEquals("lower", wire.request("GET", "/case%20name?ignored=yes", false).text());
                assertEquals("root", wire.request("GET", "/?ignored=yes", false).text());
            }
        }
    }

    @Test
    void expectContinueIsSentOnlyAfterHeadersPassLimits() throws Exception {
        try (HttpServer server = server(new HttpServerConfig().setMaxRequestBodyBytes(4))) {
            server.requestMapping("/post", (req, res) -> res.htmlBody("accepted"));
            try (Wire wire = new Wire(server)) {
                wire.send("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n");
                assertEquals(100, wire.read(false).status);
                wire.send("test");
                Reply accepted = wire.read(false);
                assertEquals(200, accepted.status);
                assertEquals("accepted", accepted.text());
            }
            try (Wire wire = new Wire(server)) {
                wire.send("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n");
                assertEquals(413, wire.read(false).status);
                assertEquals(-1, wire.input.read());
            }
        }
    }

    @Test
    void applicationInformationalStatusCannotBeFinalResponse() throws Exception {
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/informational", (req, res) -> res.setStatus(HttpResponseStatus.CONTINUE));
            try (Wire wire = new Wire(server)) {
                assertEquals(500, wire.request("GET", "/informational", false).status);
            }
        }
    }

    @Test
    void gzipResponseHasConsistentFramingAndDecodedBody() throws Exception {
        final String text = new String(new char[4096]).replace('\0', 'x');
        try (HttpServer server = server(new HttpServerConfig())) {
            server.requestMapping("/compressed", (req, res) -> res.htmlBody(text));
            try (Wire wire = new Wire(server)) {
                wire.send("GET /compressed HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n");
                Reply reply = wire.read(false);
                assertEquals(200, reply.status);
                assertEquals("gzip", reply.headers.get("content-encoding"));
                assertFalse(reply.headers.containsKey("content-length") && reply.headers.containsKey("transfer-encoding"));
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(reply.body))) {
                    byte[] buffer = new byte[1024];
                    for (int count; (count = gzip.read(buffer)) != -1;) decoded.write(buffer, 0, count);
                }
                assertEquals(text, new String(decoded.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }
}
