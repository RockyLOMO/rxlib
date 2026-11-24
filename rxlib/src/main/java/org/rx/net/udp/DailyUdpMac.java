package org.rx.net.udp;

import io.netty.buffer.ByteBuf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

/** Cleartext data with directional HMAC tags, using a fresh secret supplied by the encrypted control RPC. */
final class DailyUdpMac {
    private static final int HEADER = DailyUdpWire.HEADER_BYTES, SEQ = 8, TAG = 16;
    static final int OVERHEAD = SEQ + TAG;
    private final UdpSequenceWindow replay = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
    private final Mac tx, rx;
    private final byte[] digest = new byte[32];
    private long sent;

    DailyUdpMac(byte[] secret, UUID id, int features, boolean server) {
        if (secret == null || secret.length != 32) throw new IllegalArgumentException("missing UDP session authentication key");
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] context = ByteBuffer.allocate(18).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits()).put((byte) features).put((byte) 0).array();
            Mac up = directionalKey(derive, context);
            context[17] = 1;
            Mac down = directionalKey(derive, context);
            tx = server ? down : up;
            rx = server ? up : down;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mac directionalKey(Mac derive, byte[] context) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(derive.doFinal(context), "HmacSHA256"));
        return mac;
    }

    ByteBuf seal(ByteBuf frame) {
        if (sent == Long.MAX_VALUE) throw new IllegalStateException("UDP authentication sequence exhausted");
        int size = frame.readableBytes(), pos = frame.readerIndex();
        ByteBuf wire = frame.alloc().ioBuffer(size + OVERHEAD, size + OVERHEAD);
        try {
            wire.writeBytes(frame, pos, HEADER).writeLong(++sent).writeBytes(frame, pos + HEADER, size - HEADER);
            sign(tx, wire, 0, wire.readableBytes());
            return wire.writeBytes(digest, 0, TAG);
        } catch (RuntimeException e) {
            wire.release();
            throw e;
        }
    }

    ByteBuf open(ByteBuf wire) {
        int pos = wire.readerIndex(), len = wire.readableBytes();
        if (len < HEADER + OVERHEAD) return null;
        long seq = wire.getLong(pos + HEADER);
        if (seq <= 0 || replay.isDuplicate(seq)) return null;
        sign(rx, wire, pos, len - TAG);
        int difference = 0;
        for (int i = 0; i < TAG; i++) difference |= wire.getByte(pos + len - TAG + i) ^ digest[i];
        if (difference != 0) return null;
        replay.mark(seq);
        return wire.retainedSlice(pos + HEADER + SEQ, len - HEADER - OVERHEAD);
    }

    private void sign(Mac mac, ByteBuf wire, int pos, int len) {
        mac.update(wire.nioBuffer(pos, len));
        try {
            mac.doFinal(digest, 0);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
