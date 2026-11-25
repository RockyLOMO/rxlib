package org.rx.net.socks;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rx.net.Sockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShadowsocksPythonClientIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305"})
    @Timeout(40)
    void independentClientExchangesTcpAndUdp(String method, @TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(pythonCryptoAvailable(), "python with cryptography required");
        Path script = Paths.get(getClass().getResource("classic_aead_client.py").toURI());
        Path result = dir.resolve("interop.log");
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, "interop-fixture");
        try (ShadowsocksServer server = new ShadowsocksServer(config)) {
            int tcp = ((InetSocketAddress) server.tcpChannels.get(0).localAddress()).getPort();
            int udp = ((InetSocketAddress) server.udpChannels.get(0).localAddress()).getPort();
            Process client = new ProcessBuilder("python", script.toString(), method,
                    Integer.toString(tcp), Integer.toString(udp)).redirectErrorStream(true)
                    .redirectOutput(result.toFile()).start();
            try {
                assertTrue(client.waitFor(30, TimeUnit.SECONDS), "Python client timed out");
                assertEquals(0, client.exitValue(), () -> readResult(result));
            } finally {
                client.destroyForcibly();
            }
        }
    }

    private static String readResult(Path result) {
        try {
            return new String(Files.readAllBytes(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean pythonCryptoAvailable() throws InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("python", "-c",
                    "from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305").start();
        } catch (IOException e) {
            return false;
        }
        try {
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } finally {
            process.destroyForcibly();
        }
    }
}
