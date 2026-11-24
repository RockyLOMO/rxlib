package org.rx.net.udp;

import io.netty.buffer.ByteBuf;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Per-association directional keys. All frames, including hop probes and FEC feedback, are authenticated. */
final class DailyUdpCipher {
    private static final int HEADER = DailyUdpWire.HEADER_BYTES, SEQ = 8, TAG = 16;
    static final int OVERHEAD = SEQ + TAG;
    // Bound the total GCM invocations and blocks under a directional traffic key.
    private static final long MAX_SEQ = 1L << 24, MAX_BYTES = 1L << 36;
    private static final byte[] LABEL = "RSS UDP AES-GCM v1".getBytes(StandardCharsets.US_ASCII);
    private final Cipher tx, rx;
    private final SecretKeySpec txKey, rxKey;
    private final byte[] nonce = new byte[12];
    private final UdpSequenceWindow replay;
    private long sent, sentBytes;

    static byte[] configuredKey() {
        String encoded = System.getenv("RSS_UDP_AES_KEY");
        if (encoded == null) {
            return null;
        }
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) {
            throw new IllegalArgumentException("RSS_UDP_AES_KEY must encode 32 random bytes");
        }
        return key;
    }

    DailyUdpCipher(byte[] secret, UUID token, UUID salt, boolean server, int features) {
        if (secret == null || salt == null) {
            throw new IllegalArgumentException("UDP AES requires a deployment key and a fresh client salt");
        }
        replay = new UdpSequenceWindow((features & (DailyUdpWire.FEC | DailyUdpWire.GAME)) != 0 ? UdpSequenceWindow.FEC_SIZE : 64);
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] context = ByteBuffer.allocate(LABEL.length + 34).put(LABEL).putLong(token.getMostSignificantBits())
                    .putLong(token.getLeastSignificantBits()).putLong(salt.getMostSignificantBits())
                    .putLong(salt.getLeastSignificantBits()).put((byte) features).put((byte) 0).array();
            SecretKeySpec up = derive(derive, context);
            context[context.length - 1] = 1;
            SecretKeySpec down = derive(derive, context);
            txKey = server ? down : up;
            rxKey = server ? up : down;
            tx = Cipher.getInstance("AES/GCM/NoPadding");
            rx = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("UDP AES-128-GCM unavailable", e);
        }
    }

    private static SecretKeySpec derive(Mac mac, byte[] context) {
        byte[] key = mac.doFinal(context);
        try {
            return new SecretKeySpec(key, 0, 16, "AES");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    ByteBuf seal(ByteBuf frame) {
        int pos = frame.readerIndex(), len = frame.readableBytes() - HEADER;
        if (sent >= MAX_SEQ || sentBytes + len > MAX_BYTES) {
            throw new IllegalStateException("UDP traffic key exhausted");
        }
        long seq = ++sent;
        sentBytes += len;
        ByteBuf wire = frame.alloc().ioBuffer(HEADER + OVERHEAD + len, HEADER + OVERHEAD + len);
        boolean ok = false;
        try {
            wire.writeBytes(frame, pos, HEADER).writeLong(seq);
            crypt(tx, Cipher.ENCRYPT_MODE, txKey, seq, wire, 0, frame, pos + HEADER, len, wire);
            ok = true;
            return wire;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("UDP encryption failed", e);
        } finally {
            if (!ok) wire.release();
        }
    }

    /** Borrow input and return an owned payload once per authenticated sequence. */
    ByteBuf open(ByteBuf wire) {
        int pos = wire.readerIndex(), len = wire.readableBytes() - HEADER - SEQ;
        if (len < TAG) return null;
        long seq = wire.getLong(pos + HEADER);
        if (seq <= 0 || seq > MAX_SEQ || replay.isDuplicate(seq)) return null;
        ByteBuf data = wire.alloc().ioBuffer(Math.max(1, len - TAG));
        boolean ok = false;
        try {
            crypt(rx, Cipher.DECRYPT_MODE, rxKey, seq, wire, pos, wire, pos + HEADER + SEQ, len, data);
            replay.mark(seq);
            ok = true;
            return data;
        } catch (AEADBadTagException invalid) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("UDP decryption failed", e);
        } finally {
            if (!ok) data.release();
        }
    }

    private void crypt(Cipher cipher, int mode, SecretKeySpec key, long seq, ByteBuf header, int h,
                       ByteBuf input, int pos, int len, ByteBuf output) throws GeneralSecurityException {
        for (int i = nonce.length - 1; i >= 4; i--) {
            nonce[i] = (byte) seq;
            seq >>>= 8;
        }
        cipher.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        cipher.updateAAD(header.nioBuffer(h, HEADER + SEQ));
        int w = output.writerIndex();
        output.writerIndex(w + cipher.doFinal(input.nioBuffer(pos, len), output.nioBuffer(w, output.writableBytes())));
    }
}
