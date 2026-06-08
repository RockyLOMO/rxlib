package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.rx.io.Bytes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

public final class Udp2rawAuthenticator {
    public static final int DEFAULT_TAG_BYTES = 16;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HMAC_BYTES = 32;
    private static final ThreadLocal<Mac> HMAC = ThreadLocal.withInitial(() -> {
        try {
            return Mac.getInstance(HMAC_ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("udp2raw hmac init failed", e);
        }
    });
    private static final ThreadLocal<byte[]> HMAC_SCRATCH = ThreadLocal.withInitial(() -> new byte[HMAC_BYTES]);

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

    public static ByteBuf sign(ByteBufAllocator alloc, byte[] secret, Udp2rawFrame frame, ByteBuf payload) {
        return sign(alloc, secret, frame, payload, DEFAULT_TAG_BYTES);
    }

    public static ByteBuf sign(ByteBufAllocator alloc, byte[] secret, Udp2rawFrame frame, ByteBuf payload, int tagBytes) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        int len = tagBytes <= 0 ? DEFAULT_TAG_BYTES : Math.min(32, tagBytes);
        ByteBuf out = (alloc != null ? alloc : UnpooledByteBufAllocator.DEFAULT).heapBuffer(HMAC_BYTES, HMAC_BYTES);
        try {
            Mac mac = HMAC.get();
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            updateFrame(mac, frame);
            if (payload != null && payload.isReadable()) {
                ByteBuffer[] buffers = payload.nioBuffers(payload.readerIndex(), payload.readableBytes());
                for (ByteBuffer buffer : buffers) {
                    mac.update(buffer);
                }
            }
            if (out.hasArray()) {
                mac.doFinal(out.array(), out.arrayOffset() + out.writerIndex());
                out.writerIndex(out.writerIndex() + len);
            } else {
                byte[] full = HMAC_SCRATCH.get();
                mac.doFinal(full, 0);
                out.writeBytes(full, 0, len);
            }
            return out;
        } catch (Exception e) {
            Bytes.release(out);
            throw new IllegalStateException("udp2raw hmac failed", e);
        }
    }

    public static boolean verify(byte[] secret, Udp2rawFrame frame, ByteBuf payload) {
        ByteBuf actual = frame != null ? frame.getAuthTag() : null;
        if (actual == null || !actual.isReadable()) {
            return false;
        }
        ByteBuf expected = sign(payload != null ? payload.alloc() : UnpooledByteBufAllocator.DEFAULT,
                secret, frame, payload, actual.readableBytes());
        try {
            return equalsConstantTime(expected, actual);
        } finally {
            expected.release();
        }
    }

    private static boolean equalsConstantTime(ByteBuf expected, ByteBuf actual) {
        int len = expected.readableBytes();
        if (len != actual.readableBytes()) {
            return false;
        }
        int expectedIndex = expected.readerIndex();
        int actualIndex = actual.readerIndex();
        int diff = 0;
        for (int i = 0; i < len; i++) {
            diff |= expected.getByte(expectedIndex + i) ^ actual.getByte(actualIndex + i);
        }
        return diff == 0;
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
        updateEndpoint(mac, frame.getDestination() != null ? frame.getDestination().getHostString() : null,
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
        updateInt(mac, utf8LowerByteLength(host));
        updateUtf8Lower(mac, host);
        updateInt(mac, port);
    }

    private static int utf8LowerByteLength(String value) {
        int len = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = Character.toLowerCase(value.codePointAt(i));
            i += Character.charCount(codePoint);
            len += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
        }
        return len;
    }

    private static void updateUtf8Lower(Mac mac, String value) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = Character.toLowerCase(value.codePointAt(i));
            i += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                mac.update((byte) codePoint);
            } else if (codePoint <= 0x7FF) {
                mac.update((byte) (0xC0 | (codePoint >>> 6)));
                mac.update((byte) (0x80 | (codePoint & 0x3F)));
            } else if (codePoint <= 0xFFFF) {
                mac.update((byte) (0xE0 | (codePoint >>> 12)));
                mac.update((byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
                mac.update((byte) (0x80 | (codePoint & 0x3F)));
            } else {
                mac.update((byte) (0xF0 | (codePoint >>> 18)));
                mac.update((byte) (0x80 | ((codePoint >>> 12) & 0x3F)));
                mac.update((byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
                mac.update((byte) (0x80 | (codePoint & 0x3F)));
            }
        }
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
