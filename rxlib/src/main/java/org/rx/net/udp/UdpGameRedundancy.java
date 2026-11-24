package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Directional Game UDP state. Each physical copy is authenticated separately by DailyUdpWire. */
final class UdpGameRedundancy {
    static final int DATA_HEADER = 5, REPORT = 3;
    private static final int WINDOW = 32, HISTORY = 256;
    // Per-JVM one-second budget for optional copies. A contested reservation yields to originals.
    private static final AtomicLong globalExtras = new AtomicLong();
    private static final long FEEDBACK_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private final UdpSequenceWindow delivered = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
    private final long[] received = new long[3];
    private final byte[] sent = new byte[HISTORY];
    private long budgetTime, feedbackTime;
    private int warmupCount, extraPackets, extraBytes, next, highest, reportAt, lastReport, clean, lossy;
    private boolean sending, observing, feedback;
    int copies = 2;
    long sentCopies, sentUnique, receivedCopies, receivedUnique, lost, sampled, suppressed, changes;

    int begin(long now) {
        if (!sending) {
            sending = true;
            feedbackTime = budgetTime = now;
        }
        if (now - feedbackTime >= FEEDBACK_TIMEOUT || !feedback && warmupCount >= 64) {
            // Feedback is unknown. Bound redundant traffic until fresh observations arrive.
            change(1);
        }
        if (warmupCount < 64) warmupCount++;
        int seq = next++;
        sent[seq & (HISTORY - 1)] = 1;
        sentUnique++;
        sentCopies++;
        return seq;
    }

    boolean extra(int seq, int wireBytes, boolean writable, long now) {
        if (now - budgetTime >= TimeUnit.SECONDS.toNanos(1)) {
            budgetTime = now;
            extraPackets = extraBytes = 0;
        }
        // Bound the additional burst per association without delaying or limiting original packets.
        if (!writable || extraPackets >= 512 || wireBytes > 512 * 1024 - extraBytes || !globalExtra(wireBytes)) {
            suppressed++;
            lossy = 0;
            return false;
        }
        extraPackets++;
        extraBytes += wireBytes;
        sent[seq & (HISTORY - 1)]++;
        sentCopies++;
        return true;
    }

    private static boolean globalExtra(int bytes) {
        long previous = globalExtras.get();
        long epoch = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()) << 37;
        long used = (previous >>> 37) == (epoch >>> 37) ? previous : epoch;
        int packets = (int) ((used >>> 23) & 0x3fff);
        int wireBytes = (int) (used & 0x7fffff);
        return packets < 8192 && bytes <= 4 * 1024 * 1024 - wireBytes
                && globalExtras.compareAndSet(previous, used + (1L << 23) + bytes);
    }

    void read(ByteBuf b, ByteBufAllocator alloc, long now, boolean writable,
              Consumer<ByteBuf> deliver, Consumer<ByteBuf> reply) {
        int pos = b.readerIndex();
        if (b.readableBytes() < DATA_HEADER) return;
        int copy = b.getUnsignedByte(pos), seq = b.getInt(pos + 1);
        if (copy == REPORT) {
            if (b.readableBytes() == 8) {
                report(seq, b.getUnsignedByte(pos + 5), b.getUnsignedByte(pos + 6), b.getUnsignedByte(pos + 7), writable, now);
            }
            return;
        }
        if (copy >= received.length) return;
        receivedCopies++;
        if (!observing) {
            observing = true;
            highest = seq;
            reportAt = seq + WINDOW + 7;
        }
        int advance = seq - highest;
        if (advance > 0) {
            for (int i = 0; i < received.length; i++) received[i] = advance >= 64 ? 0L : received[i] << advance;
            highest = seq;
        }
        if (advance > -64) received[copy] |= 1L << (advance > 0 ? 0 : -advance);
        // Finalize only after eight more sequence positions, allowing ordinary reordering.
        if (seq - reportAt >= 0 && advance > 0) {
            int originals = (int) (received[0] >>> 8);
            int second = (int) (received[1] >>> 8), third = (int) (received[2] >>> 8);
            int unique = Integer.bitCount(originals | second | third), first = Integer.bitCount(originals);
            int physical = first + Integer.bitCount(second) + Integer.bitCount(third);
            sampled += WINDOW;
            lost += WINDOW - unique;
            reportAt = seq + WINDOW;
            int headroom = DailyUdpWire.HEADER_BYTES;
            ByteBuf report = alloc.ioBuffer(headroom + 8, headroom + 8).setIndex(headroom, headroom);
            reply.accept(report.writeByte(REPORT).writeInt(seq - 8).writeByte(unique).writeByte(physical).writeByte(first));
        }
        if (delivered.acceptSerial(seq)) {
            receivedUnique++;
            deliver.accept(b.retainedSlice(pos + DATA_HEADER, b.readableBytes() - DATA_HEADER));
        }
    }

    private void report(int end, int unique, int physical, int originals, boolean writable, long now) {
        int age = next - 1 - end;
        if (warmupCount < WINDOW || age < 0 || age > HISTORY - WINDOW || feedback && end - lastReport <= 0
                || unique > WINDOW || originals > unique || physical < unique || physical > 3 * unique) return;
        int actual = 0;
        for (int i = 0; i < WINDOW; i++) actual += sent[(end - i) & (HISTORY - 1)];
        if (actual < WINDOW || physical > actual) return;
        feedback = true;
        lastReport = end;
        feedbackTime = now;
        if (physical == actual) {
            lossy = 0;
            if (++clean >= 3) change(Math.max(1, copies - 1));
            return;
        }
        clean = 0;
        if (!writable || unique == 0) {
            lossy = 0;
            return;
        }
        if (copies == 1 && unique < WINDOW) {
            change(2);
        } else if (copies == 2 && unique < WINDOW && unique > originals && actual == 2 * WINDOW) {
            // Two sampled windows must show both residual logical loss and benefit from the second copy.
            if (++lossy >= 2) change(3);
        } else {
            lossy = 0;
        }
    }

    private void change(int count) {
        if (count != copies) changes++;
        copies = count;
        clean = lossy = 0;
    }
}
