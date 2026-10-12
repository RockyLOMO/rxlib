package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShadowsocksTcpDownloadIntegrationTest {
    @Test
    @Timeout(20)
    void slowReader_receivesCompleteEncryptedDownloadBeforeEof() throws Exception {
        byte[] payload = new byte[512 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket source = new ServerSocket(0)) {
            source.setSoTimeout(5000);
            Future<?> producer = executor.submit(() -> {
                try (Socket socket = source.accept()) {
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0),
                    "aes-256-gcm", "slow-download-password");
            config.setReadTimeoutSeconds(0);
            ShadowsocksServer server = new ShadowsocksServer(config);
            ICrypto crypto = ICrypto.get(config.getMethod(), config.getPassword());
            try (Socket client = new Socket()) {
                client.setReceiveBufferSize(4096);
                client.connect(server.tcpChannels.get(0).localAddress(), 5000);
                client.setSoTimeout(5000);
                ByteBuf address = Unpooled.buffer();
                try {
                    UdpManager.encode(address, "127.0.0.1", source.getLocalPort());
                    ByteBuf encrypted = crypto.encrypt(address);
                    try {
                        byte[] bytes = new byte[encrypted.readableBytes()];
                        encrypted.readBytes(bytes);
                        client.getOutputStream().write(bytes);
                        client.getOutputStream().flush();
                    } finally {
                        encrypted.release();
                    }
                } finally {
                    address.release();
                }

                Thread.sleep(200);
                ByteArrayOutputStream received = new ByteArrayOutputStream(payload.length);
                InputStream input = client.getInputStream();
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    ByteBuf encrypted = Unpooled.wrappedBuffer(chunk, 0, count);
                    try {
                        ByteBuf plaintext = crypto.decrypt(encrypted);
                        try {
                            while (plaintext.isReadable()) {
                                received.write(plaintext.readByte());
                            }
                        } finally {
                            plaintext.release();
                        }
                    } finally {
                        encrypted.release();
                    }
                    Thread.sleep(2);
                }
                producer.get(5, TimeUnit.SECONDS);
                assertArrayEquals(payload, received.toByteArray());
            } finally {
                if (crypto instanceof AutoCloseable) {
                    ((AutoCloseable) crypto).close();
                }
                server.close();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
