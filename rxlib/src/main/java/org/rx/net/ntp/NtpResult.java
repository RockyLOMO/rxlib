package org.rx.net.ntp;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;

import java.net.InetSocketAddress;

/**
 * Immutable NTP response result.
 * <p>
 * Wraps the retained inbound {@link ByteBuf} from the server response.
 * All NTP field reads are done via absolute-index {@code getXxx(offset)} calls —
 * no copy, no intermediate objects, no {@code byte[]}.
 * </p>
 * <p>
 * <strong>IMPORTANT</strong>: Call {@link #release()} when done.
 * Or wrap in try-with-resources via {@link AutoCloseable}.
 * </p>
 */
public final class NtpResult implements AutoCloseable {

    /** Raw retained packet ByteBuf (heap). Released by {@link #release()} / {@link #close()}. */
    private final ByteBuf packet;

    /** Java-millis when we received the response (t4). */
    @Getter
    private final long returnTimeMillis;

    /** Round-trip delay millis: (t4-t1)-(t3-t2). */
    @Getter
    private final long delayMillis;

    /** Clock offset millis: ((t2-t1)+(t3-t4))/2. */
    @Getter
    private final long offsetMillis;

    /** Address of the NTP server that replied. */
    @Getter
    private final InetSocketAddress serverAddress;

    NtpResult(ByteBuf packet, long returnTimeMillis, long delayMillis, long offsetMillis, InetSocketAddress serverAddress) {
        this.packet         = packet;
        this.returnTimeMillis = returnTimeMillis;
        this.delayMillis    = delayMillis;
        this.offsetMillis   = offsetMillis;
        this.serverAddress  = serverAddress;
    }

    // ---- lazy NTP field accessors — zero allocation, absolute-index reads ----

    public int getLeapIndicator()         { return NtpPacket.getLeapIndicator(packet); }
    public int getVersion()               { return NtpPacket.getVersion(packet); }
    public int getMode()                  { return NtpPacket.getMode(packet); }
    public int getStratum()               { return NtpPacket.getStratum(packet); }
    public int getPoll()                  { return NtpPacket.getPoll(packet); }
    public int getPrecision()             { return NtpPacket.getPrecision(packet); }
    public double getRootDelayMillis()    { return NtpPacket.getRootDelayMillis(packet); }
    public double getRootDispersionMillis() { return NtpPacket.getRootDispersionMillis(packet); }

    /** Originate timestamp (t1) as 64-bit NTP value. */
    public long getOriginateNtp()         { return NtpPacket.getOriginateNtp(packet); }
    /** Receive timestamp (t2) as 64-bit NTP value. */
    public long getReceiveNtp()           { return NtpPacket.getReceiveNtp(packet); }
    /** Transmit timestamp (t3) as 64-bit NTP value. */
    public long getTransmitNtp()          { return NtpPacket.getTransmitNtp(packet); }
    /** Reference timestamp as 64-bit NTP value. */
    public long getReferenceNtp()         { return NtpPacket.getReferenceNtp(packet); }

    /** Convenience: server transmit time (t3) in Java millis. */
    public long getServerTimeMillis()     { return NtpPacket.ntpToMillis(getTransmitNtp()); }

    /**
     * Best estimate of current wall-clock time in Java millis, adjusted by the clock offset.
     */
    public long getAdjustedTimeMillis()   { return returnTimeMillis + offsetMillis; }

    public void release() {
        ReferenceCountUtil.safeRelease(packet);
    }

    @Override
    public void close() {
        release();
    }

    @Override
    public String toString() {
        return "NtpResult{server=" + serverAddress +
               ", stratum=" + getStratum() +
               ", offset=" + offsetMillis + "ms" +
               ", delay=" + delayMillis + "ms" +
               ", serverTime=" + getServerTimeMillis() +
               ", adjustedTime=" + getAdjustedTimeMillis() + '}';
    }
}
