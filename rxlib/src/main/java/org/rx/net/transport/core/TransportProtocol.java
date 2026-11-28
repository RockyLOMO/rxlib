package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.rx.net.EgressCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Binary wire handshake v2 and frame constants for shared Transport Core (zero serialization dependency).
 * Handshake HMAC binds protocol version, capability flags, selected physical {@link EgressCodec},
 * compression level, UDP MTU, session ID, nonces, and peer IDs. Post-handshake AEAD encryption and
 * business compression install strictly after {@code Hello}/{@code HelloAck} + {@code FRAME_READY}.
 */
final class TransportProtocol {
    static final int MAGIC = 0x52585443; // "RXTC"
    private static final byte[] MAGIC_BYTES = new byte[]{'R', 'X', 'T', 'C'};
    static final byte VERSION = 2;

    static final int FLAG_ADAPTIVE_UDP = 1 << 0;
    static final int FLAG_AUTH = 1 << 1;
    static final int FLAG_COMPRESS = 1 << 2;
    static final int FLAG_AEAD = 1 << 3;
    /** Raw-stream carrier mode (transparent post-handshake ByteBuf stream, e.g. RSS private TCP). */
    static final int FLAG_RAW_STREAM = 1 << 4;
    /** Enables dual-port UDP hopping on AdaptiveTcpHandler (primary + alias port negotiated in HelloAck). */
    static final int FLAG_UDP_HOP = 1 << 5;
    static final int PROFILE_SHIFT = 6;
    static final int PROFILE_MASK = 0x3 << PROFILE_SHIFT;

    static final byte FRAME_DATA = 0;
    static final byte FRAME_PING = 1;
    static final byte FRAME_PONG = 2;
    static final byte FRAME_READY = 3;

    static final int CLIENT_NONCE_BYTES = 20;
    static final int SERVER_NONCE_BYTES = 16;
    static final int AUTH_TAG_BYTES = 16;
    static final int MAX_PEER_ID_BYTES = 64;

    // 4B MAGIC + 1B VER + 2B FLAGS + 1B EGRESS + 1B COMP_LEVEL + 2B MTU + 8B SESSION + 20B NONCE + 1B PEER_LEN
    static final int HELLO_FIXED_BYTES = 4 + 1 + 2 + 1 + 1 + 2 + 8 + CLIENT_NONCE_BYTES + 1;
    // 4B MAGIC + 1B VER + 2B FLAGS + 1B EGRESS + 1B COMP_LEVEL + 2B MTU + 2B UDP_PORT [+ 2B UDP_ALIAS_PORT if RAW_STREAM] + 8B SESSION + 16B NONCE + 1B PEER_LEN
    static final int HELLO_ACK_FIXED_BYTES = 4 + 1 + 2 + 1 + 1 + 2 + 2 + 8 + SERVER_NONCE_BYTES + 1;

    static int encodeProfileFlag(int profile) {
        if (profile < 0 || profile > 2) {
            throw new IllegalArgumentException("Invalid transport adaptive profile: " + profile);
        }
        return (profile & 0x3) << PROFILE_SHIFT;
    }

    static int decodeProfileFlag(int flags) {
        int p = (flags & PROFILE_MASK) >>> PROFILE_SHIFT;
        if (p > 2) {
            throw new IllegalArgumentException("Invalid wire transport adaptive profile: " + p);
        }
        return p;
    }

    private static final byte[] ADAPTIVE_KEY_DOMAIN = "rxlib-transport-core-adaptive-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AEAD_KEY_DOMAIN = "rxlib-transport-core-aead-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HELLO_AUTH_DOMAIN = "rxlib-transport-core-hello-auth-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACK_AUTH_DOMAIN = "rxlib-transport-core-ack-auth-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIGHTWEIGHT_ACK_DOMAIN = "rxlib-rss-lightweight-ack-v1".getBytes(StandardCharsets.UTF_8);
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

    static boolean hasAuthSecretBytes(byte[] authSecretBytes) {
        return authSecretBytes != null && authSecretBytes.length > 0;
    }

    static byte[] secretBytes(String authSecret) {
        return hasAuthSecret(authSecret) ? authSecret.getBytes(StandardCharsets.UTF_8) : null;
    }

    static byte[] computeHelloAuthTag(String authSecret, int flags, byte egressCode, int compLevel,
                                      int udpMtu, long sessionId, byte[] clientNonce, String peerId) {
        return computeHelloAuthTag(secretBytes(authSecret), flags, egressCode, compLevel, udpMtu, sessionId, clientNonce, peerId);
    }

    static byte[] computeHelloAuthTag(byte[] authSecretBytes, int flags, byte egressCode, int compLevel,
                                      int udpMtu, long sessionId, byte[] clientNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecretBytes, "HmacSHA256"));
            mac.update(HELLO_AUTH_DOMAIN);
            byte[] header = new byte[16];
            header[0] = VERSION;
            putShort(header, 1, flags);
            header[3] = egressCode;
            header[4] = (byte) (compLevel & 0xFF);
            putShort(header, 5, udpMtu);
            putLong(header, 7, sessionId);
            header[15] = (byte) peerLen;
            mac.update(header);
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
        return verifyHelloAuth(secretBytes(authSecret), hello);
    }

    static boolean verifyHelloAuth(byte[] authSecretBytes, Hello hello) {
        if (!hasAuthSecretBytes(authSecretBytes)) {
            return false;
        }
        if (hello == null || hello.authTag == null || hello.authTag.length != AUTH_TAG_BYTES) {
            return false;
        }
        byte[] expected = computeHelloAuthTag(authSecretBytes, hello.flags, hello.egressCode, hello.compressionLevel,
                hello.udpMtu, hello.sessionId, hello.clientNonce, hello.peerId);
        return MessageDigest.isEqual(expected, hello.authTag);
    }

    static byte[] computeHelloAckAuthTag(String authSecret, int flags, byte egressCode, int compLevel,
                                         int udpMtu, int serverUdpPort, long sessionId,
                                         byte[] clientNonce, byte[] serverNonce, String peerId) {
        return computeHelloAckAuthTag(secretBytes(authSecret), flags, egressCode, compLevel,
                udpMtu, serverUdpPort, serverUdpPort, sessionId, clientNonce, serverNonce, peerId);
    }

    static byte[] computeHelloAckAuthTag(byte[] authSecretBytes, int flags, byte egressCode, int compLevel,
                                         int udpMtu, int serverUdpPort, int serverUdpAliasPort, long sessionId,
                                         byte[] clientNonce, byte[] serverNonce, String peerId) {
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        boolean rawStream = (flags & FLAG_RAW_STREAM) != 0;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecretBytes, "HmacSHA256"));
            mac.update(ACK_AUTH_DOMAIN);
            byte[] header = new byte[rawStream ? 20 : 18];
            int p = 0;
            header[p++] = VERSION;
            putShort(header, p, flags); p += 2;
            header[p++] = egressCode;
            header[p++] = (byte) (compLevel & 0xFF);
            putShort(header, p, udpMtu); p += 2;
            putShort(header, p, serverUdpPort); p += 2;
            if (rawStream) {
                putShort(header, p, serverUdpAliasPort); p += 2;
            }
            putLong(header, p, sessionId); p += 8;
            header[p] = (byte) peerLen;
            mac.update(header);
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

    static byte[] computeLightweightAckMac(byte[] authSecretBytes, byte version, int ackFlags, byte egressCode,
                                           int serverCompLevel, int negotiatedMtu, int primaryPort, int aliasPort,
                                           long sessionId, byte[] clientNonce, byte[] serverNonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecretBytes, "HmacSHA256"));
            mac.update(LIGHTWEIGHT_ACK_DOMAIN);
            byte[] header = new byte[18];
            header[0] = version;
            header[1] = (byte) (ackFlags & 0xFF);
            header[2] = egressCode;
            header[3] = (byte) (serverCompLevel & 0xFF);
            putShort(header, 4, negotiatedMtu);
            putShort(header, 6, primaryPort);
            putShort(header, 8, aliasPort);
            putLong(header, 10, sessionId);
            mac.update(header);
            mac.update(clientNonce, 0, CLIENT_NONCE_BYTES);
            mac.update(serverNonce, 0, SERVER_NONCE_BYTES);
            return Arrays.copyOf(mac.doFinal(), AUTH_TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute lightweight ack MAC", e);
        }
    }

    static boolean verifyLightweightAckMac(byte[] authSecretBytes, byte version, int ackFlags, byte egressCode,
                                           int serverCompLevel, int negotiatedMtu, int primaryPort, int aliasPort,
                                           long sessionId, byte[] clientNonce, byte[] serverNonce, byte[] actualMac) {
        if (!hasAuthSecretBytes(authSecretBytes) || actualMac == null || actualMac.length != AUTH_TAG_BYTES) {
            return false;
        }
        byte[] expected = computeLightweightAckMac(authSecretBytes, version, ackFlags, egressCode,
                serverCompLevel, negotiatedMtu, primaryPort, aliasPort, sessionId, clientNonce, serverNonce);
        return MessageDigest.isEqual(expected, actualMac);
    }

    private static void putShort(byte[] dst, int off, int value) {
        dst[off] = (byte) (value >>> 8);
        dst[off + 1] = (byte) value;
    }

    private static void putLong(byte[] dst, int off, long value) {
        for (int i = 7; i >= 0; i--) {
            dst[off + i] = (byte) value;
            value >>>= 8;
        }
    }

    static boolean verifyHelloAckAuth(String authSecret, byte[] clientNonce, HelloAck ack) {
        if (!hasAuthSecret(authSecret)) {
            return true;
        }
        return verifyHelloAckAuth(secretBytes(authSecret), clientNonce, ack);
    }

    static boolean verifyHelloAckAuth(byte[] authSecretBytes, byte[] clientNonce, HelloAck ack) {
        if (!hasAuthSecretBytes(authSecretBytes)) {
            return false;
        }
        if (ack == null || ack.authTag == null || ack.authTag.length != AUTH_TAG_BYTES) {
            return false;
        }
        byte[] expected = computeHelloAckAuthTag(authSecretBytes, ack.flags, ack.egressCode, ack.compressionLevel,
                ack.udpMtu, ack.serverUdpPort, ack.serverUdpAliasPort, ack.sessionId, clientNonce, ack.serverNonce, ack.peerId);
        return MessageDigest.isEqual(expected, ack.authTag);
    }

    static ByteBuf encodeHello(ByteBufAllocator alloc, int flags, EgressCodec egress, int compLevel,
                               int udpMtu, long sessionId, byte[] clientNonce, String peerId, String authSecret) {
        return encodeHello(alloc, flags, egress, compLevel, udpMtu, sessionId, clientNonce, peerId, secretBytes(authSecret));
    }

    static ByteBuf encodeHello(ByteBufAllocator alloc, int flags, EgressCodec egress, int compLevel,
                               int udpMtu, long sessionId, byte[] clientNonce, String peerId, byte[] authSecretBytes) {
        boolean auth = hasAuthSecretBytes(authSecretBytes);
        int wireFlags = auth ? (flags | FLAG_AUTH) : (flags & ~FLAG_AUTH);
        byte egressCode = (egress == null ? EgressCodec.NONE : egress).wireCode();
        int safeComp = (wireFlags & FLAG_COMPRESS) != 0 ? Math.max(1, Math.min(9, compLevel)) : 0;
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int size = HELLO_FIXED_BYTES + peerLen + (auth ? AUTH_TAG_BYTES : 0);
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeShort(wireFlags & 0xFFFF);
        buf.writeByte(egressCode);
        buf.writeByte(safeComp);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeLong(sessionId);
        buf.writeBytes(clientNonce, 0, CLIENT_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        if (auth) {
            buf.writeBytes(computeHelloAuthTag(authSecretBytes, wireFlags, egressCode, safeComp, udpMtu,
                    sessionId, clientNonce, peerId), 0, AUTH_TAG_BYTES);
        }
        return buf;
    }

    private static void verifyMagicPrefix(ByteBuf in, boolean ack) {
        int readable = in.readableBytes();
        if (readable <= 0) {
            return;
        }
        int base = in.readerIndex();
        int prefixCheck = Math.min(4, readable);
        for (int i = 0; i < prefixCheck; i++) {
            if (in.getByte(base + i) != MAGIC_BYTES[i]) {
                throw new IllegalArgumentException("Invalid transport handshake" + (ack ? " ack" : "")
                        + " magic byte[" + i + "]: 0x" + Integer.toHexString(in.getUnsignedByte(base + i)));
            }
        }
        if (readable >= 5) {
            byte version = in.getByte(base + 4);
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported transport handshake" + (ack ? " ack" : "") + " version: " + version);
            }
        }
    }

    static Hello tryDecodeHello(ByteBuf in) {
        verifyMagicPrefix(in, false);
        if (in.readableBytes() < HELLO_FIXED_BYTES) {
            return null;
        }
        int base = in.readerIndex();
        int peekFlags = in.getUnsignedShort(base + 5);
        int peerLen = in.getUnsignedByte(base + HELLO_FIXED_BYTES - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport peerId too long: " + peerLen);
        }
        int authBytes = (peekFlags & FLAG_AUTH) != 0 ? AUTH_TAG_BYTES : 0;
        if (in.readableBytes() < HELLO_FIXED_BYTES + peerLen + authBytes) {
            return null;
        }
        in.skipBytes(5);
        int flags = in.readUnsignedShort();
        byte egressCode = in.readByte();
        int compLevel = in.readUnsignedByte();
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
        return new Hello(flags, egressCode, compLevel, udpMtu, sessionId, clientNonce, peerId, authTag);
    }

    static ByteBuf encodeHelloAck(ByteBufAllocator alloc, int flags, EgressCodec egress, int compLevel,
                                  int udpMtu, int serverUdpPort, long sessionId,
                                  byte[] clientNonce, byte[] serverNonce, String peerId, String authSecret) {
        return encodeHelloAck(alloc, flags, egress, compLevel, udpMtu, serverUdpPort, serverUdpPort,
                sessionId, clientNonce, serverNonce, peerId, secretBytes(authSecret));
    }

    static ByteBuf encodeHelloAck(ByteBufAllocator alloc, int flags, EgressCodec egress, int compLevel,
                                  int udpMtu, int serverUdpPort, int serverUdpAliasPort, long sessionId,
                                  byte[] clientNonce, byte[] serverNonce, String peerId, byte[] authSecretBytes) {
        boolean auth = hasAuthSecretBytes(authSecretBytes);
        boolean rawStream = (flags & FLAG_RAW_STREAM) != 0;
        int wireFlags = auth ? (flags | FLAG_AUTH) : (flags & ~FLAG_AUTH);
        byte egressCode = (egress == null ? EgressCodec.NONE : egress).wireCode();
        int safeComp = (wireFlags & FLAG_COMPRESS) != 0 ? Math.max(1, Math.min(9, compLevel)) : 0;
        byte[] peerBytes = peerId == null ? new byte[0] : peerId.getBytes(StandardCharsets.UTF_8);
        int peerLen = Math.min(peerBytes.length, MAX_PEER_ID_BYTES);
        int fixedBytes = HELLO_ACK_FIXED_BYTES + (rawStream ? 2 : 0);
        int size = fixedBytes + peerLen + (auth ? AUTH_TAG_BYTES : 0);
        ByteBuf buf = alloc.ioBuffer(size, size);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeShort(wireFlags & 0xFFFF);
        buf.writeByte(egressCode);
        buf.writeByte(safeComp);
        buf.writeShort(udpMtu & 0xFFFF);
        buf.writeShort(serverUdpPort & 0xFFFF);
        if (rawStream) {
            buf.writeShort(serverUdpAliasPort & 0xFFFF);
        }
        buf.writeLong(sessionId);
        buf.writeBytes(serverNonce, 0, SERVER_NONCE_BYTES);
        buf.writeByte(peerLen);
        if (peerLen > 0) {
            buf.writeBytes(peerBytes, 0, peerLen);
        }
        if (auth) {
            buf.writeBytes(computeHelloAckAuthTag(authSecretBytes, wireFlags, egressCode, safeComp, udpMtu,
                    serverUdpPort, serverUdpAliasPort, sessionId, clientNonce, serverNonce, peerId), 0, AUTH_TAG_BYTES);
        }
        return buf;
    }

    static HelloAck tryDecodeHelloAck(ByteBuf in) {
        verifyMagicPrefix(in, true);
        if (in.readableBytes() < 7) {
            return null;
        }
        int base = in.readerIndex();
        int peekFlags = in.getUnsignedShort(base + 5);
        boolean rawStream = (peekFlags & FLAG_RAW_STREAM) != 0;
        int fixedBytes = HELLO_ACK_FIXED_BYTES + (rawStream ? 2 : 0);
        if (in.readableBytes() < fixedBytes) {
            return null;
        }
        int peerLen = in.getUnsignedByte(base + fixedBytes - 1);
        if (peerLen > MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException("Transport server peerId too long: " + peerLen);
        }
        int authBytes = (peekFlags & FLAG_AUTH) != 0 ? AUTH_TAG_BYTES : 0;
        if (in.readableBytes() < fixedBytes + peerLen + authBytes) {
            return null;
        }
        in.skipBytes(5);
        int flags = in.readUnsignedShort();
        byte egressCode = in.readByte();
        int compLevel = in.readUnsignedByte();
        int udpMtu = in.readUnsignedShort();
        int serverUdpPort = in.readUnsignedShort();
        int serverUdpAliasPort = rawStream ? in.readUnsignedShort() : serverUdpPort;
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
        return new HelloAck(flags, egressCode, compLevel, udpMtu, serverUdpPort, serverUdpAliasPort,
                sessionId, serverNonce, peerId, authTag);
    }

    static byte[] deriveAdaptiveKey(String authSecret, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        return deriveAdaptiveKey(secretBytes(authSecret), sessionId, clientNonce, serverNonce);
    }

    static byte[] deriveAdaptiveKey(byte[] authSecretBytes, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        return hmacExpand(authSecretBytes, ADAPTIVE_KEY_DOMAIN, (byte) 1, sessionId, clientNonce, serverNonce);
    }

    /** Derives 40 bytes of directional AES-128-GCM key + nonce salt material (domain-separated from adaptive UDP key). */
    static byte[] deriveAeadKeyMaterial(String authSecret, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        return deriveAeadKeyMaterial(secretBytes(authSecret), sessionId, clientNonce, serverNonce);
    }

    static byte[] deriveAeadKeyMaterial(byte[] authSecretBytes, long sessionId, byte[] clientNonce, byte[] serverNonce) {
        if (!hasAuthSecretBytes(authSecretBytes)) {
            throw new IllegalStateException("AEAD encryption requires non-empty authSecret");
        }
        byte[] block1 = hmacExpand(authSecretBytes, AEAD_KEY_DOMAIN, (byte) 1, sessionId, clientNonce, serverNonce);
        byte[] block2 = hmacExpand(authSecretBytes, AEAD_KEY_DOMAIN, (byte) 2, sessionId, clientNonce, serverNonce);
        byte[] material = new byte[TransportAeadCodec.KEY_MATERIAL_BYTES];
        System.arraycopy(block1, 0, material, 0, 32);
        System.arraycopy(block2, 0, material, 32, TransportAeadCodec.KEY_MATERIAL_BYTES - 32);
        Arrays.fill(block1, (byte) 0);
        Arrays.fill(block2, (byte) 0);
        return material;
    }

    private static byte[] hmacExpand(byte[] authSecretBytes, byte[] domain, byte counter,
                                     long sessionId, byte[] clientNonce, byte[] serverNonce) {
        byte[] secretBytes = hasAuthSecretBytes(authSecretBytes) ? authSecretBytes : domain;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            mac.update(domain);
            mac.update(counter);
            mac.update(ByteBuffer.allocate(8).putLong(sessionId).array());
            mac.update(clientNonce);
            mac.update(serverNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive session key", e);
        }
    }

    static final class Hello {
        final int flags;
        final byte egressCode;
        final int compressionLevel;
        final int udpMtu;
        final long sessionId;
        final byte[] clientNonce;
        final String peerId;
        final byte[] authTag;

        Hello(int flags, byte egressCode, int compressionLevel, int udpMtu, long sessionId,
              byte[] clientNonce, String peerId, byte[] authTag) {
            this.flags = flags;
            this.egressCode = egressCode;
            this.compressionLevel = compressionLevel;
            this.udpMtu = udpMtu;
            this.sessionId = sessionId;
            this.clientNonce = clientNonce;
            this.peerId = peerId;
            this.authTag = authTag;
        }
    }

    static final class HelloAck {
        final int flags;
        final byte egressCode;
        final int compressionLevel;
        final int udpMtu;
        final int serverUdpPort;
        final int serverUdpAliasPort;
        final long sessionId;
        final byte[] serverNonce;
        final String peerId;
        final byte[] authTag;

        HelloAck(int flags, byte egressCode, int compressionLevel, int udpMtu, int serverUdpPort, int serverUdpAliasPort,
                 long sessionId, byte[] serverNonce, String peerId, byte[] authTag) {
            this.flags = flags;
            this.egressCode = egressCode;
            this.compressionLevel = compressionLevel;
            this.udpMtu = udpMtu;
            this.serverUdpPort = serverUdpPort;
            this.serverUdpAliasPort = serverUdpAliasPort;
            this.sessionId = sessionId;
            this.serverNonce = serverNonce;
            this.peerId = peerId;
            this.authTag = authTag;
        }
    }
}
