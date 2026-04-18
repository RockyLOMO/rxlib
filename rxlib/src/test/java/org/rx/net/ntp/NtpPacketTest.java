package org.rx.net.ntp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NtpPacket} — pure codec logic, zero network, zero byte[].
 * All buffers are constructed via Netty APIs only.
 */
@Slf4j
public class NtpPacketTest {

    // ---- helpers ----

    /** Build a synthetic 48-byte NTP server response ByteBuf (heap). */
    private ByteBuf buildServerResponse(int li, int version, int mode, int stratum,
                                        long originateNtp, long receiveNtp, long transmitNtp) {
        ByteBuf buf = Unpooled.buffer(NtpPacket.PACKET_LENGTH, NtpPacket.PACKET_LENGTH);
        buf.writeZero(NtpPacket.PACKET_LENGTH);
        // flags: LI(2)|VN(3)|Mode(3)
        buf.setByte(0, ((li & 0x3) << 6) | ((version & 0x7) << 3) | (mode & 0x7));
        buf.setByte(1, stratum & 0xFF);
        // root delay @ 4, root dispersion @ 8 — leave 0 for simplicity
        // originate @ 24
        buf.setLong(24, originateNtp);
        // receive @ 32
        buf.setLong(32, receiveNtp);
        // transmit @ 40
        buf.setLong(40, transmitNtp);
        return buf;
    }

    // ---- NtpPacket.encodeRequest tests ----

    @Test
    void encodeRequest_writesExactly48Bytes() {
        long xmitNtp = NtpPacket.millisToNtp(System.currentTimeMillis());
        ByteBuf buf = NtpPacket.encodeRequest(PooledByteBufAllocator.DEFAULT, xmitNtp);
        try {
            assertEquals(NtpPacket.PACKET_LENGTH, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void encodeRequest_flagsByteMatchesClientModeAndVersion3() {
        long xmitNtp = NtpPacket.millisToNtp(System.currentTimeMillis());
        ByteBuf buf = NtpPacket.encodeRequest(PooledByteBufAllocator.DEFAULT, xmitNtp);
        try {
            assertEquals(0, NtpPacket.getLeapIndicator(buf), "LI should be 0");
            assertEquals(3, NtpPacket.getVersion(buf),       "Version should be 3");
            assertEquals(NtpPacket.MODE_CLIENT, NtpPacket.getMode(buf), "Mode should be CLIENT(3)");
        } finally {
            buf.release();
        }
    }

    @Test
    void encodeRequest_transmitTimestampIsEmbedded() {
        long nowMillis = System.currentTimeMillis();
        long xmitNtp  = NtpPacket.millisToNtp(nowMillis);
        ByteBuf buf   = NtpPacket.encodeRequest(PooledByteBufAllocator.DEFAULT, xmitNtp);
        try {
            assertEquals(xmitNtp, NtpPacket.getTransmitNtp(buf), "Transmit NTP timestamp must be echoed");
        } finally {
            buf.release();
        }
    }

    @Test
    void encodeRequest_usesHeapBuffer() {
        ByteBuf buf = NtpPacket.encodeRequest(PooledByteBufAllocator.DEFAULT, 0L);
        try {
            assertTrue(buf.hasArray(), "Buffer must be heap-backed (no Direct)");
        } finally {
            buf.release();
        }
    }

    // ---- NtpPacket timestamp conversion tests ----

    @Test
    void millisToNtp_and_ntpToMillis_roundTrip() {
        long[] samples = {
            0L,
            System.currentTimeMillis(),
            1_000_000_000L,  // year ~2001
            2_000_000_000_000L  // far future
        };
        for (long millis : samples) {
            long ntp        = NtpPacket.millisToNtp(millis);
            long roundTrip  = NtpPacket.ntpToMillis(ntp);
            // Precision loss is at most 1ms due to fractional rounding
            assertTrue(Math.abs(roundTrip - millis) <= 1,
                "Round-trip error > 1ms for millis=" + millis + " got=" + roundTrip);
        }
    }

    @Test
    void ntpToMillis_knownEpochValue() {
        // NTP epoch 0 == 1900-01-01 00:00:00 UTC
        // Java millis for that date == -2208988800000
        long ntpZero = 0x8000000000000000L; // seconds bit-0 set = 1900 era, seconds=0 is still base
        // Actually test a known point: 1970-01-01 00:00:00 UTC = NTP seconds 2208988800
        long ntpFor1970 = (2208988800L | 0x80000000L) << 32; // MSB set -> 1900 era
        long javaMillis  = NtpPacket.ntpToMillis(ntpFor1970);
        assertEquals(0L, javaMillis, "NTP timestamp for 1970-01-01 should map to Java epoch 0");
    }

    // ---- NtpPacket field read tests ----

    @Test
    void getMode_server_returns4() {
        ByteBuf buf = buildServerResponse(0, 4, NtpPacket.MODE_SERVER, 2, 0, 0, 0);
        try {
            assertEquals(NtpPacket.MODE_SERVER, NtpPacket.getMode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void getVersion_returns_embeddedVersion() {
        ByteBuf buf = buildServerResponse(0, 4, NtpPacket.MODE_SERVER, 1, 0, 0, 0);
        try {
            assertEquals(4, NtpPacket.getVersion(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void getStratum_returns_embeddedStratum() {
        ByteBuf buf = buildServerResponse(0, 3, NtpPacket.MODE_SERVER, 2, 0, 0, 0);
        try {
            assertEquals(2, NtpPacket.getStratum(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void getOriginateNtp_returns_embeddedValue() {
        long orig  = NtpPacket.millisToNtp(System.currentTimeMillis() - 100);
        ByteBuf buf = buildServerResponse(0, 3, NtpPacket.MODE_SERVER, 2, orig, 0, 0);
        try {
            assertEquals(orig, NtpPacket.getOriginateNtp(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void getTransmitNtp_returns_embeddedValue() {
        long xmit   = NtpPacket.millisToNtp(System.currentTimeMillis() + 50);
        ByteBuf buf = buildServerResponse(0, 3, NtpPacket.MODE_SERVER, 2, 0, 0, xmit);
        try {
            assertEquals(xmit, NtpPacket.getTransmitNtp(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void absoluteIndexReads_doNotChangeReaderIndex() {
        ByteBuf buf = buildServerResponse(0, 3, NtpPacket.MODE_SERVER, 1, 0, 0, 0);
        try {
            int before = buf.readerIndex();
            NtpPacket.getMode(buf);
            NtpPacket.getStratum(buf);
            NtpPacket.getVersion(buf);
            NtpPacket.getOriginateNtp(buf);
            NtpPacket.getTransmitNtp(buf);
            assertEquals(before, buf.readerIndex(), "Absolute reads must not advance readerIndex");
        } finally {
            buf.release();
        }
    }

    // ---- NtpPacket RFC-1305 delay / offset computation tests ----

    @Test
    void computeDelay_symmetricNetwork_returnsRoundTrip() {
        // t1=0, t2=10, t3=20, t4=30 → delay = (30-0)-(20-10) = 20
        long delay = NtpPacket.computeDelay(0, 10, 20, 30);
        assertEquals(20, delay);
    }

    @Test
    void computeOffset_clockAhead_returnsPositive() {
        // t1=0, t2=15, t3=25, t4=35 → offset = ((15-0)+(25-35))/2 = (15-10)/2 = 2 (rounded down)
        long offset = NtpPacket.computeOffset(0, 15, 25, 35);
        assertEquals(2, offset);
    }

    @Test
    void computeOffset_clockBehind_returnsNegative() {
        // Server timestamps behind local: t1=20, t2=5, t3=6, t4=30
        // offset = ((5-20)+(6-30))/2 = (-15-24)/2 = -19
        long offset = NtpPacket.computeOffset(20, 5, 6, 30);
        assertEquals(-19, offset);
    }

    @Test
    void computeDelay_zeroProcessingTime_equalsRoundTripTime() {
        // t2==t3 means server processed instantly
        // t1=100, t2=200, t3=200, t4=310 → delay = (310-100)-(200-200) = 210
        long delay = NtpPacket.computeDelay(100, 200, 200, 310);
        assertEquals(210, delay);
    }
}
