package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Binary wire handshake and frame constants for shared Transport Core (zero serialization dependency). */
final class TransportProtocol {
    static final int MAGIC = 0x52585443; // "RXTC"
    static final byte VERSION = 1;
    static final int FLAG_ADAPTIVE_UDP = 1;
    static final int FLAG_AUTH = 2;

    static final byte FRAME_DATA = 0;
    static final byte FRAME_PING = 1;
    static final byte FRAME_PONG = 2;
    static final byte FRAME_READY = 3;

    static final int CLIENT_NONCE_BYTES = 20;
    static final int SERVER_NONCE_BYTES = 16;
    static final int AUTH_TAG_BYTES = 16;
    static final int MAX_PEER_ID_BYTES = 64;
    static final int HELLO_FIXED_BYTES = 4 + 1 + 1 + 2 + 8 + CLIENT_NONCE_BYTES + 1;
    static final int HELLO_ACK_FIXED_BYTES = 4 + 1 + 1 + 2 + 2 + 8 + SERVER_NONCE_BYTES + 1;

    private static final byte[] DEFAULT_KEY_DOMAIN = "rxlib-transport-core-adaptive-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_AUTH_DOMAIN = "rxlib-transport-core-hello-auth-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACK_AUTH_DOMAIN = "rxlib-transport-core-ack-auth-v1".getBytes(StandardCharsets.UTF_8);
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

    static boolean hasAuthSecret(String authSecret) {
        return authSecret != null && !authSecret.isEmpty();
    }

    static byte[] computeHelloAuthTag(String authSecret, int flags, int udpMtu, long sessionId,
                                      byte[] clientNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(HELLO_AUTH_DOMAIN);
            ByteBuffer bb = ByteBuffer.allocate(1 + 2 + 8 + 1);
            bb.put((byte) flags);
            bb.putShort((short) (udpMtu & 0xFFFF));
            bb.putLong(sessionId);
            bb.put((byte) peerLen);
            mac.update(bb.array());
            mac.update(clientNonce, 0, CLIENT_NONCE_BYTES);
            if (peerLen > 0) {
                mac.update(peerBytes, 0, peerLen);
            }
            return Arrays.copyOf(mac.doFinal(), AUTH_TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute handshake auth tag", e);
        }
    }

    static boolean verifyHelloAuth(String authSecret, Hello hello) {
        if (!hasAuthSecret(authSecret)) {
            return true;
        }
        if (hello == null || hello.authTag == null || hello.authTag.length != AUTH_TAG_BYTES) {
            return false;
        }
        byte[] expected = computeHelloAuthTag(authSecret, hello.flags, hello.udpMtu, hello.sessionId,
                hello.clientNonce, hello.peerId);
        return MessageDigest.isEqual(expected, hello.authTag);
    }

    static byte[] computeHelloAckAuthTag(String authSecret, int flags, int udpMtu, int serverUdpPort,
                                         long sessionId, byte[] clientNonce, byte[] serverNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(ACK_AUTH_DOMAIN);
            ByteBuffer bb = ByteBuffer.allocate(1 + 2 + 2 + 8 + 1);
            bb.put((byte) flags);
            bb.putShort((short) (udpMtu & 0xFFFF));
            bb.putShort((short) (serverUdpPort & 0xFFFF));
            bb.putLong(sessionId);
            bb.put((byte) peerLen);
            mac.update(bb.array());
            mac.update(clientNonce, 0, CLIENT_NONCE_BYTES);
            mac.update(serverNonce, 0, SERVER_NONCE_BYTES);
            if (peerLen > 0) {
                mac.update(peerBytes, 0, peerLen);
            }
            return Arrays.copyOf(mac.doFinal(), AUTH_TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute handshake ack auth tag", e);
        }
    }

    static boolean verifyHelloAckAuth(String authSecret, byte[] clientNonce, HelloAck ack) {
        if (!hasAuthSecret(authSecret)) {
            return true;
        }
        if (ack == null || ack.authTag == null || ack.authTag.length != AUTH_TAG_BYTES) {
            return false;
        }
        byte[] expected = computeHelloAckAuthTag(authSecret, ack.flags, ack.udpMtu, ack.serverUdpPort,
                ack.sessionId, clientNonce, ack.serverNonce, ack.peerId);
        return MessageDigest.isEqual(expected, ack.authTag);
    }

    static ByteBuf encodeHello(ByteBufAllocator alloc, int flags, int udpMtu, long sessionId,
                               byte[] clientNonce, String peerId, String authSecret) {
        boolean auth = hasAuthSecret(authSecret);
        int wireFlags = auth ? (flags | FLAG_AUTH) : (flags & ~FLAG_AUTH);
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int size = HELLO_FIXED_BYTES + peerLen + (auth ? AUTH_TAG_BYTES : 0);
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(wireFlags);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeLong(sessionId);
        buf.writeBytes(clientNonce, 0, CLIENT_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        if (auth) {
            buf.writeBytes(computeHelloAuthTag(authSecret, wireFlags, udpMtu, sessionId, clientNonce, peerId), 0, AUTH_TAG_BYTES);
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
        int peekFlags = in.getUnsignedByte(base + 5);
        int peerLen = in.getUnsignedByte(base + HELLO_FIXED_BYTES - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport peerId too long: " + peerLen);
        }
        int authBytes = (peekFlags & FLAG_AUTH) != 0 ? AUTH_TAG_BYTES : 0;
        if (in.readableBytes() < HELLO_FIXED_BYTES + peerLen + authBytes) {
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
        byte[] authTag = null;
        if (authBytes > 0) {
            authTag = new byte[AUTH_TAG_BYTES];
            in.readBytes(authTag);
        }
        return new Hello(flags, udpMtu, sessionId, clientNonce, peerId, authTag);
    }

    static ByteBuf encodeHelloAck(ByteBufAllocator alloc, int flags, int udpMtu, int serverUdpPort,
                                  long sessionId, byte[] clientNonce, byte[] serverNonce, String peerId, String authSecret) {
        boolean auth = hasAuthSecret(authSecret);
        int wireFlags = auth ? (flags | FLAG_AUTH) : (flags & ~FLAG_AUTH);
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int size = HELLO_ACK_FIXED_BYTES + peerLen + (auth ? AUTH_TAG_BYTES : 0);
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(wireFlags);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeShort(serverUdpPort & 0xFFFF);
        buf.writeLong(sessionId);
        buf.writeBytes(serverNonce, 0, SERVER_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        if (auth) {
            buf.writeBytes(computeHelloAckAuthTag(authSecret, wireFlags, udpMtu, serverUdpPort,
                    sessionId, clientNonce, serverNonce, peerId), 0, AUTH_TAG_BYTES);
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
        int peekFlags = in.getUnsignedByte(base + 5);
        int peerLen = in.getUnsignedByte(base + HELLO_ACK_FIXED_BYTES - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport server peerId too long: " + peerLen);
        }
        int authBytes = (peekFlags & FLAG_AUTH) != 0 ? AUTH_TAG_BYTES : 0;
        if (in.readableBytes() < HELLO_ACK_FIXED_BYTES + peerLen + authBytes) {
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
        byte[] authTag = null;
        if (authBytes > 0) {
            authTag = new byte[AUTH_TAG_BYTES];
            in.readBytes(authTag);
        }
        return new HelloAck(flags, udpMtu, serverUdpPort, sessionId, serverNonce, peerId, authTag);
    }

    static byte[] deriveAdaptiveKey(String authSecret, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        byte[] secretBytes = hasAuthSecret(authSecret)
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
        final byte[] authTag;

        Hello(int flags, int udpMtu, long sessionId, byte[] clientNonce, String peerId, byte[] authTag) {
            this.flags = flags;
            this.udpMtu = udpMtu;
            this.sessionId = sessionId;
            this.clientNonce = clientNonce;
            this.peerId = peerId;
            this.authTag = authTag;
        }
    }

    static final class HelloAck {
        final int flags;
        final int udpMtu;
        final int serverUdpPort;
        final long sessionId;
        final byte[] serverNonce;
        final String peerId;
        final byte[] authTag;

        HelloAck(int flags, int udpMtu, int serverUdpPort, long sessionId, byte[] serverNonce, String peerId, byte[] authTag) {
            this.flags = flags;
            this.udpMtu = udpMtu;
            this.serverUdpPort = serverUdpPort;
            this.sessionId = sessionId;
            this.serverNonce = serverNonce;
            this.peerId = peerId;
            this.authTag = authTag;
        }
    }
}
