package org.rx.net.rss.udp;

import org.rx.net.udp.UdpSequenceWindow;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Directional Game UDP state. Each physical copy is authenticated separately by DailyUdpWire. */
final class UdpGameRedundancy {
    static final int DATA_HEADER = 5, REPORT = 3;
    private static final int WINDOW = 32, SHORT_HISTORY = 256, LONG_HISTORY = 1024;
    // At most 8 MiB of extended report histories per JVM. Release on handler removal.
    private static final AtomicInteger extendedHistories = new AtomicInteger();
    private static final int MAX_EXTENDED = (8 << 20) / (LONG_HISTORY * 5);
    static final AtomicLong GLOBAL_EXTRAS = new AtomicLong(System.nanoTime());
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long FEEDBACK_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private final UdpSequenceWindow delivered = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
    private final long[] received = new long[3];
    private byte[] sent = new byte[SHORT_HISTORY];
    private int[] sentMillis = new int[SHORT_HISTORY];
    private int historySamples;
    private int baselineMillis = -1, queuedWindows;
    private final AtomicLong processBudget;
    private long budgetTime, feedbackTime;

    UdpGameRedundancy(AtomicLong processBudget) {
        this.processBudget = processBudget;
    }
    private int warmupCount, next, highest, reportAt, lastReport, clean, lossy;
    private boolean sending, observing, feedback;
    // Each sender chooses its initial multiplier. Feedback always uses the actual sent counts.
    private static final int INITIAL_COPIES = io.netty.util.internal.SystemPropertyUtil.getInt("app.rss.game.initialCopies", 1);
    int copies = INITIAL_COPIES == 2 ? 2 : 1;
    long expiredReports, invalidReports, queueSuppressed, rateSuppressed, globalSuppressed;
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
        if (historySamples < sent.length) historySamples++;
        sent[seq & (sent.length - 1)] = 1;
        sentMillis[seq & (sent.length - 1)] = (int) TimeUnit.NANOSECONDS.toMillis(now);
        sentUnique++;
        sentCopies++;
        return seq;
    }

    boolean extra(int seq, int wireBytes, boolean writable, long now) {
        // Virtual finish time limits both PPS and bytes, with eight packets of burst credit.
        long cost = Math.max((SECOND + 511) / 512, (wireBytes * SECOND + 524287) / 524288);
        long finish = now + Math.max(0L, budgetTime - now) + cost;
        if (!writable) queueSuppressed++;
        else if (finish - now > 8 * ((SECOND + 511) / 512)) rateSuppressed++;
        else if (!globalExtra(wireBytes, now)) globalSuppressed++;
        else {
            budgetTime = finish;
            sent[seq & (sent.length - 1)]++;
            sentCopies++;
            return true;
        }
        suppressed++;
        lossy = 0;
        return false;
    }

    private void expandHistory() {
        if (sent.length != SHORT_HISTORY) return;
        if (extendedHistories.incrementAndGet() > MAX_EXTENDED) {
            extendedHistories.decrementAndGet();
            return;
        }
        byte[] counts = new byte[LONG_HISTORY];
        int[] times = new int[LONG_HISTORY];
        for (int age = 1; age <= historySamples; age++) {
            int seq = next - age;
            counts[seq & (LONG_HISTORY - 1)] = sent[seq & (SHORT_HISTORY - 1)];
            times[seq & (LONG_HISTORY - 1)] = sentMillis[seq & (SHORT_HISTORY - 1)];
        }
        sent = counts;
        sentMillis = times;
    }

    void close() {
        if (sent != null && sent.length == LONG_HISTORY) extendedHistories.decrementAndGet();
        sent = null;
        sentMillis = null;
    }

    private boolean globalExtra(int bytes, long now) {
        long previous = processBudget.get();
        long cost = Math.max((SECOND + 8191) / 8192, (bytes * SECOND + 4194303) / 4194304);
        long finish = now + Math.max(0L, previous - now) + cost;
        return finish - now <= SECOND / 64 && processBudget.compareAndSet(previous, finish);
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
        if (age >= 0 && age > historySamples - WINDOW) {
            expiredReports++;
            if (age > SHORT_HISTORY - WINDOW && age <= LONG_HISTORY - WINDOW) expandHistory();
            return;
        }
        if (age < 8 || feedback && end - lastReport <= 0
                || unique > WINDOW || originals > unique || physical < unique || physical > 3 * unique) {
            invalidReports++;
            return;
        }
        int actual = 0;
        for (int i = 0; i < WINDOW; i++) actual += sent[(end - i) & (sent.length - 1)];
        if (actual < WINDOW || physical > actual) {
            invalidReports++;
            return;
        }
        feedback = true;
        lastReport = end;
        feedbackTime = now;
        // The report was triggered by end+8. Measure its round trip on this sender's clock.
        int rtt = (int) TimeUnit.NANOSECONDS.toMillis(now) - sentMillis[(end + 8) & (sent.length - 1)];
        if (rtt < 0) return;
        if (baselineMillis < 0 || rtt < baselineMillis) baselineMillis = rtt;
        boolean growing = rtt - baselineMillis > Math.max(20, baselineMillis / 2);
        queuedWindows = growing ? Math.min(2, queuedWindows + 1) : 0;
        baselineMillis += (rtt - baselineMillis) / 64;
        if (!writable || queuedWindows >= 2) {
            change(1);
            return;
        }
        if (physical == actual) {
            lossy = 0;
            if (++clean >= 3) change(Math.max(1, copies - 1));
            return;
        }
        clean = 0;
        if (growing || unique == 0) {
            lossy = 0;
            return;
        }
        if (copies == 1 && unique < WINDOW) {
            int spanMillis = sentMillis[end & (sent.length - 1)] - sentMillis[(end - WINDOW + 1) & (sent.length - 1)];
            // Two lossy feedback windows before adding load above 500 PPS.
            if (spanMillis >= 62 || ++lossy >= 2) change(2);
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
