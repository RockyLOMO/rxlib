package org.rx.net.socks;

import io.netty.buffer.ByteBuf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class Udp2rawAuthenticator {
    public static final int DEFAULT_TAG_BYTES = 16;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private Udp2rawAuthenticator() {
    }

    public static boolean requiresAuth(Udp2rawAuthMode mode, boolean newSession, int flags) {
        if (mode == null || mode == Udp2rawAuthMode.RPC_SESSION_ONLY) {
            return false;
        }
        if (mode == Udp2rawAuthMode.EVERY_PACKET_MAC) {
            return true;
        }
        return newSession || (flags & Udp2rawCodec.FLAG_NEW_CONN) != 0;
    }

    public static byte[] sign(byte[] secret, Udp2rawFrame frame, ByteBuf payload) {
        return sign(secret, frame, payload, DEFAULT_TAG_BYTES);
    }

    public static byte[] sign(byte[] secret, Udp2rawFrame frame, ByteBuf payload, int tagBytes) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        int len = tagBytes <= 0 ? DEFAULT_TAG_BYTES : Math.min(32, tagBytes);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            updateFrame(mac, frame);
            if (payload != null && payload.isReadable()) {
                ByteBuffer[] buffers = payload.nioBuffers(payload.readerIndex(), payload.readableBytes());
                for (ByteBuffer buffer : buffers) {
                    mac.update(buffer);
                }
            }
            byte[] full = mac.doFinal();
            if (len == full.length) {
                return full;
            }
            byte[] tag = new byte[len];
            System.arraycopy(full, 0, tag, 0, len);
            return tag;
        } catch (Exception e) {
            throw new IllegalStateException("udp2raw hmac failed", e);
        }
    }

    public static boolean verify(byte[] secret, Udp2rawFrame frame, ByteBuf payload) {
        byte[] actual = frame != null ? frame.getAuthTag() : null;
        if (actual == null || actual.length == 0) {
            return false;
        }
        byte[] expected = sign(secret, frame, payload, actual.length);
        return MessageDigest.isEqual(expected, actual);
    }

    private static void updateFrame(Mac mac, Udp2rawFrame frame) {
        updateInt(mac, frame.getVersion());
        updateInt(mac, frame.getFlags() & ~Udp2rawCodec.FLAG_AUTH_TAG);
        updateInt(mac, frame.getType().code());
        updateLong(mac, frame.getSessionHi());
        updateLong(mac, frame.getSessionLo());
        updateLong(mac, frame.getConnId());
        updateLong(mac, frame.getPacketSeq());
        updateEndpoint(mac, frame.getClientSource());
        updateEndpoint(mac, frame.getDestination() != null ? frame.getDestination().getHost() : null,
                frame.getDestination() != null ? frame.getDestination().getPort() : 0);
        updateEndpoint(mac, frame.getSourceAddress());
    }

    private static void updateEndpoint(Mac mac, java.net.InetSocketAddress endpoint) {
        updateEndpoint(mac, endpoint != null ? endpoint.getHostString() : null, endpoint != null ? endpoint.getPort() : 0);
    }

    private static void updateEndpoint(Mac mac, String host, int port) {
        if (host == null) {
            updateInt(mac, 0);
            return;
        }
        byte[] bytes = host.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        updateInt(mac, bytes.length);
        mac.update(bytes);
        updateInt(mac, port);
    }

    private static void updateInt(Mac mac, int value) {
        mac.update((byte) (value >>> 24));
        mac.update((byte) (value >>> 16));
        mac.update((byte) (value >>> 8));
        mac.update((byte) value);
    }

    private static void updateLong(Mac mac, long value) {
        mac.update((byte) (value >>> 56));
        mac.update((byte) (value >>> 48));
        mac.update((byte) (value >>> 40));
        mac.update((byte) (value >>> 32));
        mac.update((byte) (value >>> 24));
        mac.update((byte) (value >>> 16));
        mac.update((byte) (value >>> 8));
        mac.update((byte) value);
    }
}
