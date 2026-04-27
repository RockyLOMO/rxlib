package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.exception.InvalidException;

import java.io.Serializable;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FuryUdpClientCodecTest {
    final FuryUdpClientCodec codec = FuryUdpClientCodec.createDefault();

    @Test
    void roundTripObject() throws Exception {
        CodecPayload payload = new CodecPayload("udp-fury", new byte[2048]);
        Arrays.fill(payload.payload, (byte) 7);

        ByteBuf encoded = codec.encode(PooledByteBufAllocator.DEFAULT, payload);
        try {
            CodecPayload decoded = (CodecPayload) codec.decode(encoded);
            assertEquals(payload.name, decoded.name);
            assertArrayEquals(payload.payload, decoded.payload);
        } finally {
            encoded.release();
        }
    }

    @Test
    void roundTripDateTime() throws Exception {
        DateTime expected = DateTime.now();

        ByteBuf encoded = codec.encode(PooledByteBufAllocator.DEFAULT, expected);
        try {
            DateTime actual = (DateTime) codec.decode(encoded);
            assertEquals(expected.getTime(), actual.getTime());
            assertEquals(expected.getTimeZone().getID(), actual.getTimeZone().getID());
        } finally {
            encoded.release();
        }
    }

    @Test
    void rejectsTypeOutsideAllowlist() throws Exception {
        FuryUdpClientCodec encodeCodec = FuryUdpClientCodec.createDefault().allowPrefix("com.acme.");
        ByteBuf encoded = encodeCodec.encode(PooledByteBufAllocator.DEFAULT, new com.acme.transport.ForbiddenPojo("deny"));
        try {
            assertThrows(Exception.class, () -> codec.decode(encoded));
        } finally {
            encoded.release();
        }
    }

    @Test
    void allowPrefixLetsCustomTypeRoundTrip() throws Exception {
        FuryUdpClientCodec allowedCodec = FuryUdpClientCodec.createDefault().allowPrefix("com.acme.");
        ByteBuf encoded = allowedCodec.encode(PooledByteBufAllocator.DEFAULT, new com.acme.transport.ForbiddenPojo("allow"));
        try {
            com.acme.transport.ForbiddenPojo decoded = (com.acme.transport.ForbiddenPojo) allowedCodec.decode(encoded);
            assertEquals("allow", decoded.name);
        } finally {
            encoded.release();
        }
    }

    @Test
    void rejectsInvalidMagicVersionCodecAndPayloadLength() throws Exception {
        assertInvalidFrame((short) 0x1234, FuryUdpClientCodec.FRAME_VERSION, FuryUdpClientCodec.CODEC_ID_FURY, 0);
        assertInvalidFrame(FuryUdpClientCodec.FRAME_MAGIC, 9, FuryUdpClientCodec.CODEC_ID_FURY, 0);
        assertInvalidFrame(FuryUdpClientCodec.FRAME_MAGIC, FuryUdpClientCodec.FRAME_VERSION, 9, 0);

        ByteBuf encoded = codec.encode(PooledByteBufAllocator.DEFAULT, "payload-length");
        try {
            encoded.setInt(encoded.readerIndex() + 4, encoded.getInt(encoded.readerIndex() + 4) + 1);
            assertThrows(InvalidException.class, () -> codec.decode(encoded));
        } finally {
            encoded.release();
        }
    }

    private void assertInvalidFrame(short magic, int version, int codecId, int payloadLength) {
        ByteBuf frame = Unpooled.buffer(8);
        try {
            frame.writeShort(magic);
            frame.writeByte(version);
            frame.writeByte(codecId);
            frame.writeInt(payloadLength);
            assertThrows(InvalidException.class, () -> codec.decode(frame));
        } finally {
            frame.release();
        }
    }

    static final class CodecPayload implements Serializable {
        private static final long serialVersionUID = -1297404125577086528L;
        final String name;
        final byte[] payload;

        CodecPayload(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }
}
