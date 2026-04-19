package org.rx.net.ntp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * Pure-Netty NTP v3/v4 packet codec.
 * <p>
 * All read/write operations work directly on a pooled heap {@link ByteBuf}.
 * No {@code byte[]}, no {@code java.net.DatagramPacket}, no intermediate objects.
 * </p>
 *
 * NTP packet layout (RFC-1305 / RFC-4330), 48 bytes:
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |LI | VN  |Mode |    Stratum    |     Poll      |   Precision   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Root Delay                            |  4
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Root Dispersion                          |  8
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Reference Identifier                      | 12
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Reference Timestamp (64)                    | 16
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Originate Timestamp (64)                    | 24
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Receive Timestamp (64)                     | 32
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Transmit Timestamp (64)                    | 40
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class NtpPacket {

    // ---- packet constants ----
    public static final int PACKET_LENGTH        = 48;
    public static final int NTP_PORT             = 123;
    public static final int MODE_CLIENT          = 3;
    public static final int MODE_SERVER          = 4;
    public static final int VERSION_3            = 3;
    public static final int VERSION_4            = 4;

    // ---- byte offsets ----
    private static final int OFF_FLAGS           = 0;  // LI(2) | VN(3) | Mode(3)
    private static final int OFF_STRATUM         = 1;
    private static final int OFF_POLL            = 2;
    private static final int OFF_PRECISION       = 3;
    private static final int OFF_ROOT_DELAY      = 4;
    private static final int OFF_ROOT_DISP       = 8;
    private static final int OFF_REF_ID          = 12;
    private static final int OFF_REF_TS          = 16;
    private static final int OFF_ORIG_TS         = 24;
    private static final int OFF_RECV_TS         = 32;
    private static final int OFF_XMIT_TS         = 40;

    // ---- NTP epoch offset: 1900 -> 1970, seconds ----
    private static final long OFFSET_1900_TO_1970 = 2208988800L;
    // baseline: 1970-01-01 in Java millis
    private static final long MSB1_BASE_MILLIS = -2208988800000L;
    private static final long MSB0_BASE_MILLIS =  2085978496000L;

    // ---- encode: write a client request into a fresh pooled heap ByteBuf ----

    /**
     * Allocates and encodes a NTP client request packet.
     * The returned buffer has its writerIndex at PACKET_LENGTH and readerIndex at 0.
     * Caller is responsible for releasing the returned buffer (Netty's DatagramPacket will handle it).
     *
     * @param alloc allocator to use
     * @param transmitNtpTime 64-bit NTP transmit timestamp to embed
     * @return heap ByteBuf (ref-count = 1)
     */
    public static ByteBuf encodeRequest(ByteBufAllocator alloc, long transmitNtpTime) {
        ByteBuf buf = alloc.heapBuffer(PACKET_LENGTH, PACKET_LENGTH);
        buf.writeZero(PACKET_LENGTH);
        // flags byte: LI=0, VN=3, Mode=CLIENT
        buf.setByte(OFF_FLAGS, (VERSION_3 << 3) | MODE_CLIENT);
        setLong(buf, OFF_XMIT_TS, transmitNtpTime);
        return buf;
    }

    // ---- decode helpers: operate on the received ByteBuf without copying ----

    public static int getLeapIndicator(ByteBuf buf) {
        return (buf.getUnsignedByte(OFF_FLAGS) >> 6) & 0x3;
    }

    public static int getVersion(ByteBuf buf) {
        return (buf.getUnsignedByte(OFF_FLAGS) >> 3) & 0x7;
    }

    public static int getMode(ByteBuf buf) {
        return buf.getUnsignedByte(OFF_FLAGS) & 0x7;
    }

    public static int getStratum(ByteBuf buf) {
        return buf.getUnsignedByte(OFF_STRATUM);
    }

    public static int getPoll(ByteBuf buf) {
        return buf.getByte(OFF_POLL);
    }

    public static int getPrecision(ByteBuf buf) {
        return buf.getByte(OFF_PRECISION);
    }

    public static int getRootDelay(ByteBuf buf) {
        return buf.getInt(OFF_ROOT_DELAY);
    }

    public static double getRootDelayMillis(ByteBuf buf) {
        return getRootDelay(buf) / 65.536;
    }

    public static int getRootDispersion(ByteBuf buf) {
        return buf.getInt(OFF_ROOT_DISP);
    }

    public static double getRootDispersionMillis(ByteBuf buf) {
        return getRootDispersion(buf) / 65.536;
    }

    /** 64-bit NTP timestamp at the Originate field. */
    public static long getOriginateNtp(ByteBuf buf) {
        return getLong(buf, OFF_ORIG_TS);
    }

    /** 64-bit NTP timestamp at the Receive field. */
    public static long getReceiveNtp(ByteBuf buf) {
        return getLong(buf, OFF_RECV_TS);
    }

    /** 64-bit NTP timestamp at the Transmit field. */
    public static long getTransmitNtp(ByteBuf buf) {
        return getLong(buf, OFF_XMIT_TS);
    }

    /** 64-bit NTP timestamp at the Reference field. */
    public static long getReferenceNtp(ByteBuf buf) {
        return getLong(buf, OFF_REF_TS);
    }

    /**
     * Convert a 64-bit NTP timestamp to Java millis (same semantics as Apache commons-net TimeStamp.getTime()).
     */
    public static long ntpToMillis(long ntpTime) {
        long seconds = (ntpTime >>> 32) & 0xFFFFFFFFL;
        long fraction = ntpTime & 0xFFFFFFFFL;
        fraction = Math.round(1000D * fraction / 0x100000000L);
        long msb = seconds & 0x80000000L;
        if (msb == 0) {
            return MSB0_BASE_MILLIS + (seconds * 1000) + fraction;
        }
        return MSB1_BASE_MILLIS + (seconds * 1000) + fraction;
    }

    /**
     * Convert Java millis to 64-bit NTP timestamp.
     */
    public static long millisToNtp(long millis) {
        boolean useBase1 = millis < MSB0_BASE_MILLIS;
        long baseMillis = useBase1 ? millis - MSB1_BASE_MILLIS : millis - MSB0_BASE_MILLIS;
        long seconds = baseMillis / 1000;
        long fraction = ((baseMillis % 1000) * 0x100000000L) / 1000;
        if (useBase1) {
            seconds |= 0x80000000L;
        }
        return (seconds << 32) | fraction;
    }

    // ---- NTP offset/delay computation (RFC-1305) ----

    /**
     * Compute round-trip delay in millis.
     * delay = (t4 - t1) - (t3 - t2)
     */
    public static long computeDelay(long t1Millis, long t2Millis, long t3Millis, long t4Millis) {
        return (t4Millis - t1Millis) - (t3Millis - t2Millis);
    }

    /**
     * Compute clock offset in millis.
     * offset = ((t2 - t1) + (t3 - t4)) / 2
     */
    public static long computeOffset(long t1Millis, long t2Millis, long t3Millis, long t4Millis) {
        return ((t2Millis - t1Millis) + (t3Millis - t4Millis)) / 2;
    }

    // ---- low-level helpers (no allocation) ----

    private static long getLong(ByteBuf buf, int offset) {
        return buf.getLong(offset);
    }

    private static void setLong(ByteBuf buf, int offset, long value) {
        buf.setLong(offset, value);
    }

    private NtpPacket() {}
}
