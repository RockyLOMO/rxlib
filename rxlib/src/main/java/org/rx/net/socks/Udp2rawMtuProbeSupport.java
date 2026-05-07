package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.rx.io.Bytes;

public final class Udp2rawMtuProbeSupport {
    public static final int ACK_PAYLOAD_BYTES = 4;
    public static final int MIN_PROBE_DATAGRAM_BYTES = Udp2rawCodec.FIXED_HEADER_LENGTH + 1
            + Udp2rawAuthenticator.DEFAULT_TAG_BYTES;

    private Udp2rawMtuProbeSupport() {
    }

    public static ByteBuf encodeProbe(ByteBufAllocator alloc, byte[] secret,
            long sessionHi, long sessionLo, long seq, int targetMtu) {
        if (targetMtu < MIN_PROBE_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("udp2raw mtu probe target too small " + targetMtu);
        }
        ByteBuf payload = null;
        ByteBuf authTag = null;
        ByteBuf encoded = null;
        try {
            Udp2rawFrame frame = Udp2rawFrame.data(sessionHi, sessionLo, 0L, seq);
            frame.setType(Udp2rawFrameType.MTU_PROBE);
            frame.setFlags(Udp2rawCodec.FLAG_AUTH_TAG);
            int payloadBytes = targetMtu - MIN_PROBE_DATAGRAM_BYTES;
            payload = alloc.directBuffer(payloadBytes, payloadBytes);
            payload.writeZero(payloadBytes);
            authTag = Udp2rawAuthenticator.sign(alloc, secret, frame, payload);
            frame.setAuthTag(authTag);
            encoded = Udp2rawCodec.encode(alloc, frame, payload);
            payload = null;
            ByteBuf out = encoded;
            encoded = null;
            return out;
        } catch (Throwable e) {
            Bytes.release(payload);
            Bytes.release(encoded);
            throw e;
        } finally {
            Bytes.release(authTag);
        }
    }

    public static ByteBuf encodeAck(ByteBufAllocator alloc, byte[] secret,
            long sessionHi, long sessionLo, long seq, int acceptedMtu) {
        ByteBuf payload = null;
        ByteBuf authTag = null;
        ByteBuf encoded = null;
        try {
            Udp2rawFrame ack = Udp2rawFrame.data(sessionHi, sessionLo, 0L, seq);
            ack.setType(Udp2rawFrameType.MTU_ACK);
            ack.setFlags(Udp2rawCodec.FLAG_AUTH_TAG);
            payload = alloc.directBuffer(ACK_PAYLOAD_BYTES, ACK_PAYLOAD_BYTES);
            payload.writeInt(Math.max(0, acceptedMtu));
            authTag = Udp2rawAuthenticator.sign(alloc, secret, ack, payload);
            ack.setAuthTag(authTag);
            encoded = Udp2rawCodec.encode(alloc, ack, payload);
            payload = null;
            ByteBuf out = encoded;
            encoded = null;
            return out;
        } catch (Throwable e) {
            Bytes.release(payload);
            Bytes.release(encoded);
            throw e;
        } finally {
            Bytes.release(authTag);
        }
    }

    public static int readAckAcceptedMtu(ByteBuf payload) {
        if (payload == null || payload.readableBytes() != ACK_PAYLOAD_BYTES) {
            return -1;
        }
        int acceptedMtu = payload.getInt(payload.readerIndex());
        return acceptedMtu > 0 ? acceptedMtu : -1;
    }
}
