package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthLimiterTest {

    @Test
    void configValidationAndOverflowSafety() {
        BandwidthLimitConfig unlimited = BandwidthLimitConfig.unlimited();
        assertTrue(unlimited.isValid());
        assertFalse(unlimited.isEnabled());
        assertEquals(0L, unlimited.uploadBytesPerSecond());
        assertEquals(0L, unlimited.downloadBytesPerSecond());

        BandwidthLimitConfig valid = new BandwidthLimitConfig(1024, 5120);
        assertTrue(valid.isValid());
        assertTrue(valid.isEnabled());
        assertEquals(1024L * 1024L, valid.uploadBytesPerSecond());
        assertEquals(5120L * 1024L, valid.downloadBytesPerSecond());

        BandwidthLimitConfig huge = new BandwidthLimitConfig(Long.MAX_VALUE, Long.MAX_VALUE / 512L);
        assertTrue(huge.isValid());
        assertEquals(Long.MAX_VALUE, huge.uploadBytesPerSecond());
        assertEquals(Long.MAX_VALUE, huge.downloadBytesPerSecond());

        BandwidthLimitConfig negUp = new BandwidthLimitConfig(-1, 100);
        assertFalse(negUp.isValid());
        assertThrows(IllegalArgumentException.class, negUp::validate);

        BandwidthLimitConfig negDown = new BandwidthLimitConfig(0, -5);
        assertFalse(negDown.isValid());
        assertThrows(IllegalArgumentException.class, negDown::validate);
    }

    @Test
    void tokenBucketExactRateLowAndHighSpeedNoOverflow() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        // 1 KiB/s = 1024 B/s, burst = 16 KiB
        BandwidthLimiter limiter = new BandwidthLimiter("low-rate",
                new BandwidthLimitConfig(1L, 0L), clock::get);

        assertTrue(limiter.isEnabled(BandwidthLimiter.Direction.UPLOAD));
        assertFalse(limiter.isEnabled(BandwidthLimiter.Direction.DOWNLOAD));
        assertEquals(0L, limiter.tryConsumeTcp(BandwidthLimiter.Direction.DOWNLOAD, 1_000_000));
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.DOWNLOAD, 65000));

        // Exhaust initial 16 KiB burst
        assertEquals(0L, limiter.tryConsumeTcp(BandwidthLimiter.Direction.UPLOAD, 16 * 1024));
        long wait = limiter.tryConsumeTcp(BandwidthLimiter.Direction.UPLOAD, 1024);
        assertEquals(TimeUnit.SECONDS.toNanos(1L), wait);

        // Advance in tiny 500 us increments (2000 ticks = 1.0s -> must refill exact 1024 bytes without rounding loss)
        for (int i = 0; i < 2000; i++) {
            clock.addAndGet(500_000L);
            if (i < 1999) {
                assertTrue(limiter.tryConsumeTcp(BandwidthLimiter.Direction.UPLOAD, 1024) > 0L);
            }
        }
        assertEquals(0L, limiter.tryConsumeTcp(BandwidthLimiter.Direction.UPLOAD, 1024));

        // High speed (100 GiB/s) + large elapsed time must not overflow long
        BandwidthLimiter fast = new BandwidthLimiter("fast",
                new BandwidthLimitConfig(100L * 1024L * 1024L, 100L * 1024L * 1024L), clock::get);
        clock.addAndGet(TimeUnit.HOURS.toNanos(24));
        assertEquals(0L, fast.tryConsumeTcp(BandwidthLimiter.Direction.UPLOAD, 1024 * 1024));
        assertTrue(fast.tryConsumeUdp(BandwidthLimiter.Direction.DOWNLOAD, 65000));
    }

    @Test
    void jumboUdpDatagramAllowedOnLowRateAndRepaysDebt() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        // 8 KiB/s (burst 16 KiB), send 24 KiB jumbo UDP datagram
        BandwidthLimiter limiter = new BandwidthLimiter("jumbo",
                new BandwidthLimitConfig(8L, 8L), clock::get);

        // Drain burst to 0 first
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 16 * 1024));
        // Jumbo packet > burstCap when tokens == 0 is admitted once (going to -24 KiB debt)
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 24 * 1024));
        // Immediate next packet must be rejected while in debt
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 256));

        // After 2 seconds (+16 KiB -> -8 KiB), still in debt -> rejected
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 256));

        // After 1 more second (+8 KiB -> 0), jumbo packet can pass again (average = 24 KiB / 3 s = 8 KiB/s)
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 24 * 1024));
    }

    @Test
    void dynamicConfigureNeverManufacturesExtraBurstCredit() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        BandwidthLimiter limiter = new BandwidthLimiter("dyn",
                new BandwidthLimitConfig(64L, 0L), clock::get);

        // Drain full initial burst (16 KiB)
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 16 * 1024));
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 1024));

        // Raise rate to 1024 KiB/s at same timestamp: must NOT refill burst immediately
        limiter.configure(new BandwidthLimitConfig(1024L, 0L));
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 1024));

        // Lower rate to 32 KiB/s at same timestamp: still empty
        limiter.configure(new BandwidthLimitConfig(32L, 0L));
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 1024));

        // Advance 0.5s at 32 KiB/s -> 16 KiB refilled
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        assertTrue(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 16 * 1024));
        assertFalse(limiter.tryConsumeUdp(BandwidthLimiter.Direction.UPLOAD, 1));
    }

    @Test
    void multiReasonReadPauseCoordinatesBackpressureConnectAndBandwidth() {
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            assertTrue(ch.config().isAutoRead());
            assertFalse(Sockets.isReadPaused(ch));

            Sockets.pauseRead(ch, Sockets.READ_PAUSE_BACKPRESSURE);
            assertFalse(ch.config().isAutoRead());
            assertEquals(Sockets.READ_PAUSE_BACKPRESSURE, Sockets.readPauseMask(ch));

            Sockets.pauseRead(ch, Sockets.READ_PAUSE_BANDWIDTH);
            Sockets.pauseRead(ch, Sockets.READ_PAUSE_CONNECT_PENDING);
            assertFalse(ch.config().isAutoRead());

            // Clearing backpressure alone must NOT re-enable autoRead
            Sockets.resumeRead(ch, Sockets.READ_PAUSE_BACKPRESSURE);
            assertFalse(ch.config().isAutoRead());

            // Clearing connect pending alone must NOT re-enable autoRead
            Sockets.resumeRead(ch, Sockets.READ_PAUSE_CONNECT_PENDING);
            assertFalse(ch.config().isAutoRead());

            // Clearing the final reason restores autoRead
            Sockets.resumeRead(ch, Sockets.READ_PAUSE_BANDWIDTH);
            assertTrue(ch.config().isAutoRead());
            assertEquals(0, Sockets.readPauseMask(ch));
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void tcpPacerChunksLargeBufferPreservesOrderAndDefersEofWithoutLeaks() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        BandwidthLimiter limiter = new BandwidthLimiter("pacer",
                new BandwidthLimitConfig(32L, 0L), clock::get);
        EmbeddedChannel ch = new EmbeddedChannel();
        List<ByteBuf> emitted = new ArrayList<>();
        AtomicBoolean eofFired = new AtomicBoolean();
        try {
            TcpBandwidthPacer pacer = new TcpBandwidthPacer(
                    ch, null, BandwidthLimiter.Direction.UPLOAD, () -> limiter, emitted::add);

            // 48 KiB single buffer (> 16 KiB initial burst)
            byte[] src = new byte[48 * 1024];
            for (int i = 0; i < src.length; i++) {
                src[i] = (byte) (i * 31 + 7);
            }
            assertTrue(pacer.offer(Unpooled.wrappedBuffer(src)));
            assertTrue(pacer.hasPending());
            assertFalse(ch.config().isAutoRead());
            assertTrue(pacer.deferEof(() -> eofFired.set(true)));
            assertFalse(eofFired.get());

            // Step fake clock + embedded event loop until drained
            for (int step = 0; step < 30 && pacer.hasPending(); step++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                ch.advanceTimeBy(250, TimeUnit.MILLISECONDS);
                ch.runScheduledPendingTasks();
            }

            assertFalse(pacer.hasPending());
            assertTrue(eofFired.get());
            assertTrue(ch.config().isAutoRead());
            assertEquals(0L, limiter.pendingTcpBytes());
            assertEquals(0, limiter.pendingTcpTasks());
            assertEquals(0L, BandwidthLimiter.processPendingTcpBytes());
            assertEquals(0, BandwidthLimiter.processPendingTcpTasks());

            // Verify byte-for-byte integrity
            int pos = 0;
            for (ByteBuf b : emitted) {
                while (b.isReadable()) {
                    assertEquals(src[pos++], b.readByte());
                }
                b.release();
            }
            assertEquals(src.length, pos);
            pacer.close();
        } finally {
            for (ByteBuf b : emitted) {
                if (b.refCnt() > 0) {
                    b.release();
                }
            }
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void standaloneOptInBandwidthChannelHandlerDoesNotAffectGlobalFlowControl() {
        AtomicLong clock = new AtomicLong(2_000_000_000L);
        BandwidthLimiter limiter = new BandwidthLimiter("standalone",
                new BandwidthLimitConfig(16L, 16L), clock::get);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            assertTrue(NetworkFlowControl.installBandwidthLimiter(ch, limiter));
            assertSame(limiter, NetworkFlowControl.bandwidthLimiter(ch));
            assertNull(ch.pipeline().get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER));

            InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 9999);
            // Exhaust 16 KiB UDP upload burst
            assertTrue(ch.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(new byte[16 * 1024]), peer)));
            DatagramPacket passed = ch.readInbound();
            assertNotNull(passed);
            passed.release();

            // Next UDP datagram is dropped cleanly (no leak)
            assertFalse(ch.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(new byte[1024]), peer)));
            assertNull(ch.readInbound());
            assertEquals(1L, limiter.snapshot().droppedUploadPackets);
            assertEquals(1024L, limiter.snapshot().droppedUploadBytes);
        } finally {
            ch.finishAndReleaseAll();
        }
    }
}
