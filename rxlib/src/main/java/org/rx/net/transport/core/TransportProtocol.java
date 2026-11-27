package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

/** Binary wire handshake and frame constants for shared Transport Core (zero serialization dependency). */
final class TransportProtocol {
    static final int MAGIC = 0x52585443; // "RXTC"
    static final byte VERSION = 1;
    static final int FLAG_ADAPTIVE_UDP = 1;

    static final byte FRAME_DATA = 0;
    static final byte FRAME_PING = 1;
    static final byte FRAME_PONG = 2;
    static final byte FRAME_READY = 3;

    static final int CLIENT_NONCE_BYTES = 20;
    static final int SERVER_NONCE_BYTES = 16;
    static final int MAX_PEER_ID_BYTES = 64;
    static final int HELLO_FIXED_BYTES = 4 + 1 + 1 + 2 + 8 + CLIENT_NONCE_BYTES + 1;
    static final int HELLO_ACK_FIXED_BYTES = 4 + 1 + 1 + 2 + 2 + 8 + SERVER_NONCE_BYTES + 1;

    private static final byte[] DEFAULT_KEY_DOMAIN = "rxlib-transport-core-adaptive-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TransportProtocol() {
    }

    static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static long randomSessionId() {
        long id;
        do {
            id = RANDOM.nextLong();
        } while (id == 0L);
        return id;
    }

    static String resolvePeerId(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return truncatePeerId(configured.trim());
        }
        return UUID.randomUUID().toString();
    }

    private static String truncatePeerId(String peerId) {
        byte[] bytes = peerId.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PEER_ID_BYTES) {
            return peerId;
        }
        return new String(bytes, 0, MAX_PEER_ID_BYTES, StandardCharsets.UTF_8);
    }

    static ByteBuf encodeHello(ByteBufAllocator alloc, int flags, int udpMtu, long sessionId, byte[] clientNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int size = HELLO_FIXED_BYTES + peerLen;
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(flags);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeLong(sessionId);
        buf.writeBytes(clientNonce, 0, CLIENT_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        return buf;
    }

    static Hello tryDecodeHello(ByteBuf in) {
        if (in.readableBytes() < HELLO_FIXED_BYTES) {
            return null;
        }
        int base = in.readerIndex();
        int magic = in.getInt(base);
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid transport handshake magic: 0x" + Integer.toHexString(magic));
        }
        byte version = in.getByte(base + 4);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported transport handshake version: " + version);
        }
        int peerLen = in.getUnsignedByte(base + HELLO_FIXED_BYTES - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport peerId too long: " + peerLen);
        }
        if (in.readableBytes() < HELLO_FIXED_BYTES + peerLen) {
            return null;
        }
        in.skipBytes(5);
        int flags = in.readUnsignedByte();
        int udpMtu = in.readUnsignedShort();
        long sessionId = in.readLong();
        byte[] clientNonce = new byte[CLIENT_NONCE_BYTES];
        in.readBytes(clientNonce);
        in.readUnsignedByte(); // peerLen
        String peerId = "";
        if (peerLen > 0) {
            byte[] peerBytes = new byte[peerLen];
            in.readBytes(peerBytes);
            peerId = new String(peerBytes, StandardCharsets.UTF_8);
        }
        return new Hello(flags, udpMtu, sessionId, clientNonce, peerId);
    }

    static ByteBuf encodeHelloAck(ByteBufAllocator alloc, int flags, int udpMtu, int serverUdpPort,
                                  long sessionId, byte[] serverNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int size = HELLO_ACK_FIXED_BYTES + peerLen;
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(flags);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeShort(serverUdpPort & 0xFFFF);
        buf.writeLong(sessionId);
        buf.writeBytes(serverNonce, 0, SERVER_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        return buf;
    }

    static HelloAck tryDecodeHelloAck(ByteBuf in) {
        if (in.readableBytes() < HELLO_ACK_FIXED_BYTES) {
            return null;
        }
        int base = in.readerIndex();
        int magic = in.getInt(base);
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid transport handshake ack magic: 0x" + Integer.toHexString(magic));
        }
        byte version = in.getByte(base + 4);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported transport handshake ack version: " + version);
        }
        int peerLen = in.getUnsignedByte(base + HELLO_ACK_FIXED_BYTES - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport server peerId too long: " + peerLen);
        }
        if (in.readableBytes() < HELLO_ACK_FIXED_BYTES + peerLen) {
            return null;
        }
        in.skipBytes(5);
        int flags = in.readUnsignedByte();
        int udpMtu = in.readUnsignedShort();
        int serverUdpPort = in.readUnsignedShort();
        long sessionId = in.readLong();
        byte[] serverNonce = new byte[SERVER_NONCE_BYTES];
        in.readBytes(serverNonce);
        in.readUnsignedByte(); // peerLen
        String peerId = "";
        if (peerLen > 0) {
            byte[] peerBytes = new byte[peerLen];
            in.readBytes(peerBytes);
            peerId = new String(peerBytes, StandardCharsets.UTF_8);
        }
        return new HelloAck(flags, udpMtu, serverUdpPort, sessionId, serverNonce, peerId);
    }

    static byte[] deriveAdaptiveKey(String authSecret, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        byte[] secretBytes = authSecret != null && !authSecret.isEmpty()
                ? authSecret.getBytes(StandardCharsets.UTF_8)
                : DEFAULT_KEY_DOMAIN;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            mac.update(DEFAULT_KEY_DOMAIN);
            mac.update(ByteBuffer.allocate(8).putLong(sessionId).array());
            mac.update(clientNonce);
            mac.update(serverNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive adaptive UDP key", e);
        }
    }

    static final class Hello {
        final int flags;
        final int udpMtu;
        final long sessionId;
        final byte[] clientNonce;
        final String peerId;

        Hello(int flags, int udpMtu, long sessionId, byte[] clientNonce, String peerId) {
            this.flags = flags;
            this.udpMtu = udpMtu;
            this.sessionId = sessionId;
            this.clientNonce = clientNonce;
            this.peerId = peerId;
        }
    }

    static final class HelloAck {
        final int flags;
        final int udpMtu;
        final int serverUdpPort;
        final long sessionId;
        final byte[] serverNonce;
        final String peerId;

        HelloAck(int flags, int udpMtu, int serverUdpPort, long sessionId, byte[] serverNonce, String peerId) {
            this.flags = flags;
            this.udpMtu = udpMtu;
            this.serverUdpPort = serverUdpPort;
            this.sessionId = sessionId;
            this.serverNonce = serverNonce;
            this.peerId = peerId;
        }
    }
}
