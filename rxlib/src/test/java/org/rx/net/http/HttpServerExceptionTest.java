package org.rx.net.http;

import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class HttpServerExceptionTest {
    @Test
    public void sslHandshakeException_isRecognizedThroughDecoderException() {
        SSLHandshakeException handshake = new SSLHandshakeException("Received fatal alert: certificate_unknown");
        assertSame(handshake, HttpServer.findSslHandshakeException(new DecoderException(handshake)));
        assertNull(HttpServer.findSslHandshakeException(new RuntimeException("boom")));
    }
}
