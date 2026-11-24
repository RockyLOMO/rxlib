package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.udp.UdpDailyFec;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Ordered bytes over an authenticated UDP path with a reliable TCP repair lane. Event-loop confined.
 * Inputs are borrowed. Every output callback takes ownership of one reference. */
public final class AdaptiveStream implements AutoCloseable {
    static final int DATA = 0, ACK = 1, GAP = 2, PING = 4, PONG = 5, SACK = 6;
    public static final int GAME = 1, WEAK = 2;
    private static final int HEADER = 5;
    public static final int MAX_TCP_FRAME = 16384 + HEADER;
    public static final int MAX_UDP_FRAME = 1250;
    private static final int CHUNK = MAX_UDP_FRAME - UdpDailyFec.MAX_OVERHEAD - HEADER;
    // Profile v1 allows 256 slots per direction. Reserve worst-case retained payload in 64-slot
    // increments only when needed, across all enhanced streams in this JVM.
    private static final int MAX_WINDOW = 256, MAX_RECOVERY_BYTES = 32 << 20;
    private static final AtomicInteger recoveryBytes = new AtomicInteger();
    private static final int WINDOW = 64;
    private static final long REORDER_GRACE = TimeUnit.MILLISECONDS.toNanos(40);
    private static final long MIN_REPAIR = TimeUnit.MILLISECONDS.toNanos(80);
    private static final long ACK_DELAY = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long PROBE_INTERVAL = TimeUnit.SECONDS.toNanos(2);

    private final ByteBufAllocator alloc;
    private final Consumer<ByteBuf> tcpOut, udpOut, deliver;
    private final Consumer<ByteBuf> decoded = this::decoded;
    private final LongSupplier clock;
    private final UdpDailyFec fec;
    private final int profile;
    private int sendSlots, receiveSlots, sackBase, reliableUntil;
    private boolean hasReliablePrefix;
    private long sackReceived, stalledSince;
    private final ArrayDeque<ByteBuf> pending = new ArrayDeque<>();
    private ByteBuf[] reordered = new ByteBuf[WINDOW];
    private int nextTx, nextRx, probeId, sinceAck, buffered;
    private long deadline, nextProbe, gapDeadline, ackDeadline, lastPing;
    private long repairDelay = MIN_REPAIR, reorderGrace = REORDER_GRACE;
    private long srtt, variation, sampleTime;
    private int sampleSeq;
    private boolean sampling;
    private boolean ready, probing, closed, gapRequested;

    public AdaptiveStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                          Consumer<ByteBuf> deliver, LongSupplier nanoClock) {
        this(alloc, tcp, udp, deliver, nanoClock, 0);
    }

    public AdaptiveStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                          Consumer<ByteBuf> deliver, LongSupplier nanoClock, int profile) {
        if (profile < 0 || profile > WEAK) throw new IllegalArgumentException("unknown adaptive profile");
        this.profile = profile;
        this.alloc = alloc;
        this.tcpOut = tcp;
        this.udpOut = udp;
        this.deliver = deliver;
        this.clock = nanoClock;
        this.nextProbe = clock.getAsLong();
        this.fec = new UdpDailyFec(alloc, nanoClock, profile == WEAK, 32);
    }

    public boolean usingUdp() {
        return ready;
    }

    public void write(ByteBuf bytes) {
        if (closed) {
            throw new IllegalStateException("closed stream");
        }
        int offset = bytes.readerIndex();
        while (offset < bytes.writerIndex()) {
            if (ready && !sendCapacity()) {
                drainPendingToTcp("reason=window");
            }
            int n = Math.min(bytes.writerIndex() - offset, ready ? CHUNK : MAX_TCP_FRAME - HEADER);
            ByteBuf frame = alloc.ioBuffer(HEADER + n, HEADER + n)
                    .writeByte(DATA).writeInt(nextTx++).writeBytes(bytes, offset, n);
            if (ready) {
                if (!sampling) {
                    sampleSeq = nextTx - 1;
                    sampleTime = clock.getAsLong();
                    sampling = true;
                }
                if (pending.isEmpty()) {
                    deadline = clock.getAsLong() + repairDelay;
                }
                pending.add(frame);
                fec.write(frame, udpOut);
            } else {
                tcpOut.accept(frame);
            }
            offset += n;
        }
    }

    public void tcp(ByteBuf frame) {
        if (closed) {
            return;
        }
        int i = frame.readerIndex(), size = frame.readableBytes();
        if (size < HEADER || size > MAX_TCP_FRAME) {
            throw new IllegalArgumentException("invalid TCP stream frame size");
        }
        int type = frame.getUnsignedByte(i);
        boolean selective = type == SACK && profile == WEAK && size == HEADER + Long.BYTES;
        if ((type == ACK || type == GAP) && size == HEADER || selective) {
            int ack = frame.getInt(i + 1);
            if (pending.isEmpty()) return;
            int count = ack - pending.peek().getInt(1);
            if (count < 0 || count > pending.size()) return;
            if (count > 0) {
                if (sampling && ack - sampleSeq > 0) {
                    if (type == ACK) updateRtt(clock.getAsLong() - sampleTime);
                    sampling = false;
                }
                while (count-- > 0) pending.remove().release();
                deadline = clock.getAsLong() + repairDelay;
                if (ack - sackBase >= Long.SIZE) sackReceived = 0;
                if (hasReliablePrefix && ack - reliableUntil >= 0) hasReliablePrefix = false;
                if (pending.isEmpty()) {
                    releaseSendSlots();
                    sackReceived = 0;
                    return;
                }
            }
            if (selective) {
                repairHoles(ack, frame.getLong(i + HEADER));
            } else if (type == GAP) {
                if (hasReliablePrefix && ack - reliableUntil < 0) return;
                sampling = false;
                ByteBuf repair = pending.peek().retainedDuplicate();
                int payload = repair.readableBytes() - HEADER;
                tcpOut.accept(repair);
                reliableUntil = ack + 1;
                hasReliablePrefix = true;
                recordRepair(1, payload, "reason=gap");
            }
        } else if (type == DATA && size > HEADER) {
            data(frame, false);
        } else {
            throw new IllegalArgumentException("invalid TCP stream frame");
        }
    }

    private void repairHoles(int base, long received) {
        int width = Long.SIZE - Long.numberOfLeadingZeros(received);
        if (width == 0 || (received & 1) != 0 || width > pending.size()
                || sackReceived == received && sackBase == base) return;
        sackBase = base;
        sackReceived = received;
        sampling = false;
        int index = 0, frames = 0, bytes = 0;
        for (ByteBuf frame : pending) {
            if ((received & (1L << index)) == 0 && (!hasReliablePrefix || base + index - reliableUntil >= 0)) {
                bytes += frame.readableBytes() - HEADER;
                frames++;
                tcpOut.accept(frame.retainedDuplicate());
            }
            if (++index == width) break;
        }
        // Every preceding slot is either buffered at the receiver or already on this TCP channel.
        if (!hasReliablePrefix || base + width - reliableUntil > 0) reliableUntil = base + width;
        hasReliablePrefix = true;
        recordRepair(frames, bytes, "reason=sack");
    }

    private static boolean reserveSlots(int slots) {
        int bytes = slots * MAX_UDP_FRAME;
        if (recoveryBytes.addAndGet(bytes) <= MAX_RECOVERY_BYTES) return true;
        recoveryBytes.addAndGet(-bytes);
        return false;
    }

    private boolean sendCapacity() {
        if (profile == 0) return pending.size() < WINDOW;
        if (pending.size() < sendSlots) return true;
        if (sendSlots == MAX_WINDOW || sendSlots != 0 && srtt < REORDER_GRACE) return false;
        int grow = Math.max(WINDOW, sendSlots);
        if (!reserveSlots(grow)) return false;
        sendSlots += grow;
        return true;
    }

    private boolean receiveCapacity(int gap) {
        if (profile == 0) return gap <= WINDOW;
        if (gap <= receiveSlots) return true;
        if (gap > MAX_WINDOW) return false;
        int size = gap <= WINDOW ? WINDOW : gap <= 2 * WINDOW ? 2 * WINDOW : MAX_WINDOW;
        if (!reserveSlots(size - receiveSlots)) return false;
        receiveSlots = size;
        if (size > reordered.length) {
            ByteBuf[] grown = new ByteBuf[size];
            for (int i = 1; i <= reordered.length; i++) {
                int seq = nextRx + i;
                grown[seq & (size - 1)] = reordered[seq & (reordered.length - 1)];
            }
            reordered = grown;
        }
        return true;
    }

    private void releaseSendSlots() {
        if (sendSlots != 0) recoveryBytes.addAndGet(-sendSlots * MAX_UDP_FRAME);
        sendSlots = 0;
    }

    private void releaseReceiveSlots() {
        if (receiveSlots != 0) recoveryBytes.addAndGet(-receiveSlots * MAX_UDP_FRAME);
        receiveSlots = 0;
    }

    public void udp(ByteBuf frame) {
        int size = frame.readableBytes();
        if (closed || size < HEADER || size > MAX_UDP_FRAME) {
            return;
        }
        int i = frame.readerIndex(), type = frame.getUnsignedByte(i);
        if (type == PING || type == PONG) {
            if (size != HEADER) {
                return;
            }
            int id = frame.getInt(i + 1);
            if (type == PING) {
                udpOut.accept(control(PONG, id));
            } else if (probing && probeId == id) {
                updateRtt(clock.getAsLong() - lastPing);
                ready = true;
                probing = false;
            }
        } else {
            fec.read(frame, decoded, udpOut);
        }
    }

    // Sample one un-repaired frame per cumulative ACK cycle. No per-frame timestamp allocation.
    private void updateRtt(long elapsed) {
        long sample = Math.max(1L, Math.min(elapsed, PROBE_INTERVAL));
        if (srtt == 0) {
            srtt = sample;
            variation = sample / 2;
        } else {
            variation += (Math.abs(sample - srtt) - variation) / 4;
            srtt += (sample - srtt) / 8;
        }
        reorderGrace = Math.max(REORDER_GRACE, Math.min(TimeUnit.MILLISECONDS.toNanos(150), srtt / 4));
        repairDelay = Math.min(PROBE_INTERVAL, Math.max(MIN_REPAIR, srtt + 4 * variation + ACK_DELAY));
    }

    private void decoded(ByteBuf frame) {
        try {
            if (frame.readableBytes() > HEADER && frame.readableBytes() <= HEADER + CHUNK
                    && frame.getUnsignedByte(frame.readerIndex()) == DATA) {
                data(frame, true);
            }
        } finally {
            frame.release();
        }
    }

    private void data(ByteBuf frame, boolean fromUdp) {
        int i = frame.readerIndex();
        int seq = frame.getInt(i + 1), gap = seq - nextRx;
        if (gap < 0) {
            return;
        }
        if (gap > 0) {
            if (!fromUdp) {
                throw new IllegalStateException("out-of-order TCP repair");
            }
            if (!receiveCapacity(gap)) return;
            int slot = seq & (reordered.length - 1);
            if (reordered[slot] == null) {
                if (buffered++ == 0) {
                    stalledSince = clock.getAsLong();
                    gapDeadline = stalledSince + reorderGrace;
                }
                reordered[slot] = frame.retainedSlice(i + HEADER, frame.readableBytes() - HEADER);
            }
            return;
        }

        boolean wasBlocked = buffered > 0;
        int previous = nextRx++;
        deliver.accept(frame.retainedSlice(i + HEADER, frame.readableBytes() - HEADER));
        while (!closed) {
            int slot = nextRx & (reordered.length - 1);
            ByteBuf next = reordered[slot];
            if (next == null) {
                break;
            }
            reordered[slot] = null;
            buffered--;
            nextRx++;
            deliver.accept(next);
        }
        if (wasBlocked) {
            long now = clock.getAsLong();
            DiagnosticMetrics.record("rss.tcp.adaptive.deliveryStallMillis", (now - stalledSince) / 1_000_000.0);
            stalledSince = now;
            if (buffered == 0) releaseReceiveSlots();
        }
        gapRequested = false;
        if (buffered > 0) {
            gapDeadline = clock.getAsLong() + reorderGrace;
        }
        if (fromUdp || nextRx - previous > 1) {
            if (sinceAck == 0) {
                ackDeadline = clock.getAsLong() + ACK_DELAY;
            }
            sinceAck += nextRx - previous;
            if (sinceAck >= 8 && !closed) {
                acknowledge();
            }
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        if (!pending.isEmpty() && now - deadline >= 0) {
            drainPendingToTcp("reason=timeout");
        }
        if (!ready && now - nextProbe >= 0) {
            lastPing = now;
            nextProbe = now + PROBE_INTERVAL;
            probing = true;
            udpOut.accept(control(PING, ++probeId));
        }
        fec.tick(udpOut);
        if (buffered > 0 && !gapRequested && now - gapDeadline >= 0) {
            // Give FEC/reordering time first. Repair an isolated gap without switching the stream off UDP.
            gapRequested = true;
            sinceAck = 0;
            long received = 0;
            if (profile == WEAK) {
                for (int offset = 1; offset < Long.SIZE; offset++) {
                    if (reordered[(nextRx + offset) & (reordered.length - 1)] != null) received |= 1L << offset;
                }
            }
            tcpOut.accept(received == 0 ? control(GAP, nextRx) : alloc.ioBuffer(HEADER + Long.BYTES, HEADER + Long.BYTES)
                    .writeByte(SACK).writeInt(nextRx).writeLong(received));
        } else if (sinceAck > 0 && now - ackDeadline >= 0) {
            acknowledge();
        }
    }

    /** Earliest needed wakeup, or -1 for an idle established stream. Recheck following I/O. */
    public long nextDelayMillis() {
        if (closed) {
            return -1;
        }
        long now = clock.getAsLong();
        long delay = ready ? Long.MAX_VALUE : nextProbe - now;
        if (!pending.isEmpty()) delay = Math.min(delay, deadline - now);
        if (buffered > 0 && !gapRequested) delay = Math.min(delay, gapDeadline - now);
        if (sinceAck > 0) delay = Math.min(delay, ackDeadline - now);
        long fecDelay = fec.nextDelayMillis();
        if (delay == Long.MAX_VALUE) return fecDelay;
        long millis = Math.max(0, (delay + 999999L) / 1000000L);
        return fecDelay < 0 ? millis : Math.min(fecDelay, millis);
    }

    private void acknowledge() {
        sinceAck = 0;
        tcpOut.accept(control(ACK, nextRx));
    }

    private ByteBuf control(int type, int value) {
        return alloc.ioBuffer(HEADER, HEADER).writeByte(type).writeInt(value);
    }

    /** Hand all outstanding bytes to the TCP channel in sequence before a TCP FIN or a path change. */
    public void drainToTcp() {
        drainPendingToTcp("reason=fin");
    }

    private static void recordRepair(int frames, int bytes, String reason) {
        if (frames == 0 || !DiagnosticMetrics.isEnabled()) return;
        DiagnosticMetrics.record("rss.tcp.adaptive.repairFrames", frames, reason);
        DiagnosticMetrics.record("rss.tcp.adaptive.repairPayloadBytes", bytes, reason);
        DiagnosticMetrics.record("rss.tcp.adaptive.repairFramedBytes", bytes + frames * (HEADER + 2), reason);
        if ("reason=timeout".equals(reason)) {
            DiagnosticMetrics.record("rss.tcp.adaptive.timeoutRepairFrames", frames);
            DiagnosticMetrics.record("rss.tcp.adaptive.timeoutRepairBytes", bytes);
        }
    }

    private void drainPendingToTcp(String reason) {
        ready = probing = sampling = false;
        nextProbe = clock.getAsLong() + PROBE_INTERVAL;
        fec.cancelPendingParity();
        int bytes = 0, frames = 0;
        while (!pending.isEmpty()) {
            ByteBuf frame = pending.remove();
            if (hasReliablePrefix && frame.getInt(1) - reliableUntil < 0) {
                frame.release();
            } else {
                bytes += frame.readableBytes() - HEADER;
                frames++;
                tcpOut.accept(frame);
            }
        }
        sackReceived = 0;
        hasReliablePrefix = false;
        releaseSendSlots();
        recordRepair(frames, bytes, reason);
    }

    @Override public void close() {
        closed = true;
        ready = probing = false;
        fec.close();
        while (!pending.isEmpty()) {
            pending.remove().release();
        }
        releaseSendSlots();
        releaseReceiveSlots();
        for (int i = 0; i < reordered.length; i++) {
            if (reordered[i] != null) {
                reordered[i].release();
                reordered[i] = null;
            }
        }
    }
}
