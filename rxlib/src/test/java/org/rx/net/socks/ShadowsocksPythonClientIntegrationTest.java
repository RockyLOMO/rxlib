package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowsocksPythonClientIntegrationTest {
    @Test
    @Timeout(20)
    void pythonClient_connectsThroughAes128GcmShadowsocksServer() throws Exception {
        runPythonClient(CipherKind.AES_128_GCM.getCipherName(), "python-aes128-gcm", "python-aes128-gcm-ok");
    }

    @Test
    @Timeout(20)
    void pythonClient_connectsThroughAes256GcmShadowsocksServer() throws Exception {
        runPythonClient(CipherKind.AES_256_GCM.getCipherName(), "python-aes256-gcm", "python-aes256-gcm-ok");
    }

    @Test
    @Timeout(20)
    void pythonClient_connectsThroughChaCha20Poly1305ShadowsocksServer() throws Exception {
        runPythonClient(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "python-chacha20", "python-chacha20-ok");
    }

    private void runPythonClient(String method, String password, String expectedPayload) throws Exception {
        Assumptions.assumeTrue(pythonCryptoAvailable(), "python cryptography is unavailable");

        ServerBootstrap echoBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.writeAndFlush(msg);
            }
        }));
        Channel echoChannel = echoBootstrap.bind(Sockets.newAnyEndpoint(0)).sync().channel();
        ShadowsocksServer server = new ShadowsocksServer(new ShadowsocksConfig(Sockets.newAnyEndpoint(0),
                method, password));
        try {
            int echoPort = ((InetSocketAddress) echoChannel.localAddress()).getPort();
            int ssPort = ((InetSocketAddress) server.tcpChannels.get(0).localAddress()).getPort();

            Process process = new ProcessBuilder("python", "-c", pythonClientScript(),
                    String.valueOf(ssPort), String.valueOf(echoPort), password, method, expectedPayload).start();
            boolean exited = process.waitFor(8, TimeUnit.SECONDS);
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());

            assertEquals(true, exited, stderr);
            assertEquals(0, process.exitValue(), stderr);
            assertEquals(expectedPayload, stdout.trim());
        } finally {
            server.close();
            echoChannel.close();
            Sockets.closeBootstrap(echoBootstrap);
        }
    }

    private static boolean pythonCryptoAvailable() throws Exception {
        Process process = new ProcessBuilder("python", "-c",
                "from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305").start();
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        return exited && process.exitValue() == 0;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String pythonClientScript() {
        return String.join("\n",
                "import hashlib, hmac, os, socket, struct, sys",
                "from cryptography.hazmat.primitives.ciphers.aead import AESGCM",
                "from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305",
                "ss_port = int(sys.argv[1])",
                "echo_port = int(sys.argv[2])",
                "password = sys.argv[3].encode()",
                "method = sys.argv[4]",
                "payload = sys.argv[5].encode()",
                "def key_len():",
                "    if method == 'aes-128-gcm':",
                "        return 16",
                "    return 32",
                "def evp_bytes_to_key(pwd):",
                "    out = b''",
                "    prev = b''",
                "    n = key_len()",
                "    while len(out) < n:",
                "        prev = hashlib.md5(prev + pwd).digest()",
                "        out += prev",
                "    return out[:n]",
                "def hkdf_sha1(key, salt):",
                "    prk = hmac.new(salt, key, hashlib.sha1).digest()",
                "    out = b''",
                "    prev = b''",
                "    i = 1",
                "    n = key_len()",
                "    while len(out) < n:",
                "        prev = hmac.new(prk, prev + b'ss-subkey' + bytes([i]), hashlib.sha1).digest()",
                "        out += prev",
                "        i += 1",
                "    return out[:n]",
                "def inc(n):",
                "    for i in range(len(n)):",
                "        n[i] = (n[i] + 1) & 255",
                "        if n[i] != 0:",
                "            break",
                "def recvn(sock, n):",
                "    data = b''",
                "    while len(data) < n:",
                "        chunk = sock.recv(n - len(data))",
                "        if not chunk:",
                "            raise RuntimeError('short read')",
                "        data += chunk",
                "    return data",
                "master = evp_bytes_to_key(password)",
                "salt = os.urandom(key_len())",
                "subkey = hkdf_sha1(master, salt)",
                "enc_nonce = bytearray(12)",
                "dec_nonce = bytearray(12)",
                "def new_aead(k):",
                "    if method == 'aes-128-gcm':",
                "        return AESGCM(k)",
                "    if method == 'aes-256-gcm':",
                "        return AESGCM(k)",
                "    if method == 'chacha20-ietf-poly1305':",
                "        return ChaCha20Poly1305(k)",
                "    raise RuntimeError('unsupported method ' + method)",
                "aead = new_aead(subkey)",
                "def encrypt_chunk(plain):",
                "    global enc_nonce",
                "    out = aead.encrypt(bytes(enc_nonce), struct.pack('>H', len(plain)), None)",
                "    inc(enc_nonce)",
                "    out += aead.encrypt(bytes(enc_nonce), plain, None)",
                "    inc(enc_nonce)",
                "    return out",
                "def decrypt_chunk(sock, dec_subkey):",
                "    dec = new_aead(dec_subkey)",
                "    global dec_nonce",
                "    length = dec.decrypt(bytes(dec_nonce), recvn(sock, 18), None)",
                "    inc(dec_nonce)",
                "    size = struct.unpack('>H', length)[0]",
                "    plain = dec.decrypt(bytes(dec_nonce), recvn(sock, size + 16), None)",
                "    inc(dec_nonce)",
                "    return plain",
                "addr = b'\\x01' + socket.inet_aton('127.0.0.1') + struct.pack('>H', echo_port)",
                "with socket.create_connection(('127.0.0.1', ss_port), timeout=5) as sock:",
                "    sock.sendall(salt + encrypt_chunk(addr + payload))",
                "    resp_salt = recvn(sock, key_len())",
                "    resp_subkey = hkdf_sha1(master, resp_salt)",
                "    data = decrypt_chunk(sock, resp_subkey)",
                "    sys.stdout.write(data.decode())");
    }
}
