package org.rx.net.rss.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.rss.udp.AdaptiveUdpFec;
import org.rx.net.rss.profile.RssProfile;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Ordered bytes over an authenticated UDP path with a reliable TCP repair lane. Event-loop confined.
 * Inputs are borrowed. Every output callback takes ownership of one reference. */
public final class AdaptiveTcpStream implements AutoCloseable {
    static final int DATA = 0, ACK = 1, GAP = 2, PING = 4, PONG = 5, SACK = 6;
    public static final int GAME = RssProfile.GAME_ID, WEAK = RssProfile.WEAK_ID;
    private static final int HEADER = 5;
    public static final int MAX_TCP_FRAME = 16384 + HEADER;
    public static final int MAX_UDP_FRAME = 1250;
    private final int chunk;
    // Profile v1 allows 256 slots per direction. Reserve worst-case retained payload in 64-slot
    // increments only when needed, across all enhanced streams in this JVM.
    private static final int MAX_RECOVERY_BYTES = 32 << 20;
    private static final AtomicInteger recoveryBytes = new AtomicInteger();
    private static final int WINDOW = 64;
    private static final long REORDER_GRACE = TimeUnit.MILLISECONDS.toNanos(40);
    private static final long MIN_REPAIR = TimeUnit.MILLISECONDS.toNanos(80);
    private static final long ACK_DELAY = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long PROBE_INTERVAL = TimeUnit.SECONDS.toNanos(2);

    private static final long MAX_PROBE_DELAY = TimeUnit.SECONDS.toNanos(30);
    private static final long PROBE_IDLE = TimeUnit.SECONDS.toNanos(4);
    private long probeInterval = PROBE_INTERVAL, lastWrite, probeCount;
    private boolean wroteData, probePaused;
    private long windowDelay, lastWindow, pathSince, tcpNanos;
    private long toUdp, toTcp, windowFallbacks, timeoutFallbacks, finFallbacks, tcpDelivered, udpDelivered;
    private int peakPending;
    private final ByteBufAllocator alloc;
    private final Consumer<ByteBuf> tcpOut, udpOut, deliver;
    private final Consumer<ByteBuf> decoded = this::decoded;
    private final LongSupplier clock;
    private final AdaptiveUdpFec fec;
    private final RssProfile policy;
    // Both candidates use existing DATA semantics. Reliable TCP remains the final repair lane.
    static final boolean UDP_TAIL = !"tcp".equalsIgnoreCase(System.getProperty("app.rss.weak.tailProbe", "udp"));
    private final boolean udpTail;
    private long tailDeadline, nextTailAllowed;
    private boolean tailArmed, tailSent, udpProbeOutstanding;
    long udpTailProbes, probesAckedBeforeTcp, probesHandedToTcp, filteredDuplicates;
    private static final int ACK_FOR_UDP = 1, ACK_FOR_TCP = 2;
    private int ackOrigins;
    private long udpAcks, tcpAcks, mixedAcks, gapsSent, sacksSent, ignoredAcks, ordinaryTcpFrames;
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
    private boolean peerProbed, ready, probing, closed, gapRequested;

    public AdaptiveTcpStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                          Consumer<ByteBuf> deliver, LongSupplier nanoClock) {
        this(alloc, tcp, udp, deliver, nanoClock, 0);
    }

    public AdaptiveTcpStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                          Consumer<ByteBuf> deliver, LongSupplier nanoClock, int profile) {
        this(alloc, tcp, udp, deliver, nanoClock, profile, UDP_TAIL);
    }

    AdaptiveTcpStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                   Consumer<ByteBuf> deliver, LongSupplier nanoClock, int profile, boolean udpTail) {
        this(alloc, tcp, udp, deliver, nanoClock, profile, udpTail, MAX_UDP_FRAME);
    }

    AdaptiveTcpStream(ByteBufAllocator alloc, Consumer<ByteBuf> tcp, Consumer<ByteBuf> udp,
                   Consumer<ByteBuf> deliver, LongSupplier nanoClock, int profile, boolean udpTail, int udpFrameLimit) {
        this.chunk = Math.min(udpFrameLimit, MAX_UDP_FRAME) - AdaptiveUdpFec.MAX_OVERHEAD - HEADER;
        if (chunk <= 0) throw new IllegalArgumentException("Invalid adaptive UDP frame budget");
        this.udpTail = udpTail;
        this.policy = RssProfile.negotiated(profile);
        this.alloc = alloc;
        this.tcpOut = tcp;
        this.udpOut = udp;
        this.deliver = deliver;
        this.clock = nanoClock;
        pathSince = this.nextProbe = clock.getAsLong();
        this.fec = new AdaptiveUdpFec(alloc, nanoClock, policy == RssProfile.WEAK, 32);
    }

    public boolean usingUdp() {
        return ready;
    }

    public void write(ByteBuf bytes) {
        if (closed) {
            throw new IllegalStateException("closed stream");
        }
        if (!ready && bytes.isReadable()) {
            long now = clock.getAsLong();
            if (probePaused) {
                boolean longIdle = !wroteData || now - lastWrite >= MAX_PROBE_DELAY;
                if (longIdle) {
                    probeInterval = PROBE_INTERVAL;
                }
                long cooldown = windowDelay != 0 ? lastWindow + windowDelay : 0;
                long earliest = Math.max(cooldown, lastPing + (longIdle ? PROBE_INTERVAL : probeInterval));
                nextProbe = Math.max(now, earliest);
                probePaused = false;
            }
            wroteData = true;
            lastWrite = now;
        } else if (ready && bytes.isReadable()) {
            int chunks = 1 + (bytes.readableBytes() - 1) / chunk;
            int maxSlots = policy.maxWindow == WINDOW || srtt < REORDER_GRACE
                    ? Math.max(WINDOW, sendSlots) : policy.maxWindow;
            if (pending.size() + chunks > maxSlots) {
                drainPendingToTcp("reason=window");
            }
        }
        int offset = bytes.readerIndex();
        while (offset < bytes.writerIndex()) {
            if (ready && !sendCapacity()) {
                drainPendingToTcp("reason=window");
            }
            int n = Math.min(bytes.writerIndex() - offset, ready ? chunk : MAX_TCP_FRAME - HEADER);
            ByteBuf frame = alloc.ioBuffer(HEADER + n, HEADER + n)
                    .writeByte(DATA).writeInt(nextTx++).writeBytes(bytes, offset, n);
            if (ready) {
                if (!sampling) {
                    sampleSeq = nextTx - 1;
                    sampleTime = clock.getAsLong();
                    sampling = true;
                }
                if (pending.isEmpty()) {
                    scheduleRepair();
                } else {
                    tailArmed = false;
                }
                pending.add(frame);
                peakPending = Math.max(peakPending, pending.size());
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
        boolean selective = type == SACK && policy == RssProfile.WEAK && size == HEADER + Long.BYTES;
        if ((type == ACK || type == GAP) && size == HEADER || selective) {
            int ack = frame.getInt(i + 1);
            if (pending.isEmpty()) {
                if (type == ACK) ignoredAcks++;
                return;
            }
            int count = ack - pending.peek().getInt(1);
            if (count < 0 || count > pending.size()) return;
            long received = selective ? frame.getLong(i + HEADER) : 0;
            if (selective && (received == 0 || (received & 1L) != 0
                    || Long.SIZE - Long.numberOfLeadingZeros(received) > pending.size() - count)) return;
            if (count > 0) {
                if (sampling && ack - sampleSeq > 0) {
                    if (type == ACK) updateRtt(clock.getAsLong() - sampleTime);
                    sampling = false;
                }
                if (udpProbeOutstanding) {
                    probesAckedBeforeTcp++;
                    udpProbeOutstanding = false;
                }
                while (count-- > 0) pending.remove().release();
                tailSent = false;
                scheduleRepair();
                tailArmed &= pending.size() == 1;
                if (ack - sackBase >= Long.SIZE) sackReceived = 0;
                if (hasReliablePrefix && ack - reliableUntil >= 0) hasReliablePrefix = false;
                if (pending.isEmpty()) {
                    releaseSendSlots();
                    sackReceived = 0;
                    return;
                }
            }
            if (selective || type == GAP) tailArmed = false;
            if (selective) {
                repairHoles(ack, received);
            } else if (type == GAP) {
                if (hasReliablePrefix && ack - reliableUntil < 0) return;
                sampling = false;
                probeHandedToTcp();
                ByteBuf repair = pending.peek().retainedDuplicate();
                int payload = repair.readableBytes() - HEADER;
                tcpOut.accept(repair);
                reliableUntil = ack + 1;
                hasReliablePrefix = true;
                if (nextTx - reliableUntil <= 0) fec.cancelPendingParity();
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
        if (sackReceived == received && sackBase == base) return;
        sackBase = base;
        sackReceived = received;
        sampling = false;
        probeHandedToTcp();
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
        if (nextTx - reliableUntil <= 0) fec.cancelPendingParity();
        recordRepair(frames, bytes, "reason=sack");
    }

    private static boolean reserveSlots(int slots) {
        int bytes = slots * MAX_UDP_FRAME;
        if (recoveryBytes.addAndGet(bytes) <= MAX_RECOVERY_BYTES) return true;
        recoveryBytes.addAndGet(-bytes);
        return false;
    }

    private boolean sendCapacity() {
        if (policy.maxWindow == WINDOW) return pending.size() < WINDOW;
        if (pending.size() < sendSlots) return true;
        if (sendSlots == policy.maxWindow || sendSlots != 0 && srtt < REORDER_GRACE) return false;
        int grow = Math.max(WINDOW, sendSlots);
        if (!reserveSlots(grow)) return false;
        sendSlots += grow;
        return true;
    }

    private boolean receiveCapacity(int gap) {
        if (policy.maxWindow == WINDOW) return gap <= WINDOW;
        if (gap <= receiveSlots) return true;
        if (gap > policy.maxWindow) return false;
        int size = gap <= WINDOW ? WINDOW : gap <= 2 * WINDOW ? 2 * WINDOW : policy.maxWindow;
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
                peerProbed = true;
                udpOut.accept(control(PONG, id));
            } else if (probing && probeId == id) {
                long now = clock.getAsLong();
                updateRtt(now - lastPing);
                tcpNanos += now - pathSince;
                pathSince = now;
                probeInterval = PROBE_INTERVAL;
                probePaused = false;
                toUdp++;
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
        repairDelay = Math.min(PROBE_INTERVAL, Math.max(MIN_REPAIR, srtt + Math.max(4 * variation, policy == RssProfile.WEAK ? srtt / 2 : 0) + ACK_DELAY));
    }

    private void decoded(ByteBuf frame) {
        try {
            if (frame.readableBytes() > HEADER && frame.readableBytes() <= HEADER + chunk
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
            filteredDuplicates++;
            if (policy == RssProfile.WEAK) requestAck(1, fromUdp);
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
            } else {
                filteredDuplicates++;
            }
            return;
        }

        boolean wasBlocked = buffered > 0;
        int previous = nextRx++;
        int payloadBytes = frame.readableBytes() - HEADER;
        if (fromUdp) udpDelivered += payloadBytes;
        else tcpDelivered += payloadBytes;
        deliver.accept(frame.retainedSlice(i + HEADER, payloadBytes));
        while (!closed) {
            int slot = nextRx & (reordered.length - 1);
            ByteBuf next = reordered[slot];
            if (next == null) {
                break;
            }
            reordered[slot] = null;
            buffered--;
            nextRx++;
            udpDelivered += next.readableBytes();
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
        // Only payloads that fit an original fast-path frame can be TCP repairs.
        // Keep acknowledging ambiguous small frames, including peers running the original weak method.
        if (fromUdp || nextRx - previous > 1 || policy == RssProfile.WEAK && peerProbed
                && (wasBlocked || frame.readableBytes() <= HEADER + chunk)) {
            requestAck(nextRx - previous, fromUdp);
        } else if (policy == RssProfile.WEAK && peerProbed) {
            ordinaryTcpFrames++;
        }
    }

    private void requestAck(int count, boolean fromUdp) {
        ackOrigins |= fromUdp ? ACK_FOR_UDP : ACK_FOR_TCP;
        if (sinceAck == 0) ackDeadline = clock.getAsLong() + ACK_DELAY;
        sinceAck += count;
        if (sinceAck >= 8 && !closed) acknowledge();
    }

    private void scheduleRepair() {
        long now = clock.getAsLong();
        deadline = now + repairDelay;
        // RTT plus delayed ACK and a jitter margin. Suppress when no useful lead over the RTO remains.
        long probeAfter = Math.max(MIN_REPAIR / 2, srtt + ACK_DELAY + Math.max(ACK_DELAY, variation / 2));
        tailDeadline = now + probeAfter;
        tailArmed = policy == RssProfile.WEAK && !tailSent && probeAfter + ACK_DELAY < repairDelay
                && (nextTailAllowed == 0 || tailDeadline - nextTailAllowed >= 0);
    }

    private void probeTail(long now) {
        tailArmed = false;
        if (pending.size() != 1 || hasReliablePrefix) return;
        ByteBuf last = pending.peek();
        tailSent = true;
        sampling = false;
        nextTailAllowed = now + Math.max(MIN_REPAIR, srtt);
        if (udpTail) {
            // Same stream sequence, fresh FEC DATA/SHARD sequence and fresh outer authentication.
            udpTailProbes++;
            udpProbeOutstanding = true;
            fec.writeTailProbe(last, udpOut);
            DiagnosticMetrics.record("rss.tcp.adaptive.udpTailProbeBytes", last.readableBytes());
        } else {
            hasReliablePrefix = true;
            reliableUntil = last.getInt(1) + 1;
            tcpOut.accept(last.retainedDuplicate());
            recordRepair(1, last.readableBytes() - HEADER, "reason=tail");
        }
        // Allow the reply one measured round trip before handing the stream back to TCP.
        deadline = Math.max(deadline, now + srtt + ACK_DELAY + Math.max(ACK_DELAY, variation));
    }

    public void tick() {
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        if (!pending.isEmpty() && now - deadline >= 0) {
            drainPendingToTcp("reason=timeout");
        } else if (tailArmed && now - tailDeadline >= 0) {
            probeTail(now);
        }
        if (!ready && !probePaused && now - nextProbe >= 0) {
            if (probeCount != 0 && (!wroteData || lastWrite < lastPing && now - lastWrite >= PROBE_IDLE)) {
                probePaused = true;
            } else {
                lastPing = now;
                long lower = Math.min(probeInterval, MAX_PROBE_DELAY - MAX_PROBE_DELAY / 8);
                nextProbe = now + lower + ThreadLocalRandom.current().nextLong(probeInterval / 8);
                probeInterval = Math.min(MAX_PROBE_DELAY, probeInterval * 2);
                probing = true;
                probeCount++;
                udpOut.accept(control(PING, ++probeId));
            }
        }
        fec.tick(udpOut);
        if (buffered > 0 && !gapRequested && now - gapDeadline >= 0) {
            // Give FEC/reordering time first. Repair an isolated gap without switching the stream off UDP.
            gapRequested = true;
            sinceAck = ackOrigins = 0;
            long received = 0;
            if (policy == RssProfile.WEAK) {
                for (int offset = 1; offset < Long.SIZE; offset++) {
                    if (reordered[(nextRx + offset) & (reordered.length - 1)] != null) received |= 1L << offset;
                }
            }
            if (received == 0) gapsSent++;
            else sacksSent++;
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
        long delay = ready || probePaused ? Long.MAX_VALUE : nextProbe - now;
        if (!pending.isEmpty()) delay = Math.min(delay, deadline - now);
        if (tailArmed && !pending.isEmpty()) delay = Math.min(delay, tailDeadline - now);
        if (buffered > 0 && !gapRequested) delay = Math.min(delay, gapDeadline - now);
        if (sinceAck > 0) delay = Math.min(delay, ackDeadline - now);
        long fecDelay = fec.nextDelayMillis();
        if (delay == Long.MAX_VALUE) return fecDelay;
        long millis = Math.max(0, (delay + 999999L) / 1000000L);
        return fecDelay < 0 ? millis : Math.min(fecDelay, millis);
    }

    private void acknowledge() {
        if (ackOrigins == ACK_FOR_UDP) udpAcks++;
        else if (ackOrigins == ACK_FOR_TCP) tcpAcks++;
        else mixedAcks++;
        sinceAck = ackOrigins = 0;
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

    private void probeHandedToTcp() {
        if (udpProbeOutstanding) {
            probesHandedToTcp++;
            udpProbeOutstanding = false;
        }
    }

    private void drainPendingToTcp(String reason) {
        probeHandedToTcp();
        tailArmed = tailSent = false;
        long now = clock.getAsLong();
        boolean wasReady = ready;
        if (wasReady) {
            // Recent UDP writes justify one reprobe after handing pending data to TCP.
            wroteData = true;
            lastWrite = now;
            probePaused = false;
            toTcp++;
            if ("reason=window".equals(reason)) windowFallbacks++;
            else if ("reason=timeout".equals(reason)) timeoutFallbacks++;
            else if ("reason=fin".equals(reason)) finFallbacks++;
            pathSince = now;
        }
        ready = probing = sampling = false;
        if ("reason=window".equals(reason)) {
            windowDelay = windowDelay != 0 && now - lastWindow < TimeUnit.MINUTES.toNanos(1)
                    ? Math.min(MAX_PROBE_DELAY, windowDelay * 2) : PROBE_INTERVAL;
            lastWindow = now;
            nextProbe = now + windowDelay;
        } else if (wasReady) {
            nextProbe = now + PROBE_INTERVAL;
        }
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
        if (closed) return;
        closed = true;
        if (DiagnosticMetrics.isEnabled()) {
            if (policy == RssProfile.WEAK) {
                DiagnosticMetrics.record("rss.tcp.weak.ackSent", udpAcks, "dataPath=udp");
                DiagnosticMetrics.record("rss.tcp.weak.ackSent", tcpAcks, "dataPath=tcp");
                DiagnosticMetrics.record("rss.tcp.weak.ackSent", mixedAcks, "dataPath=mixed");
                DiagnosticMetrics.record("rss.tcp.weak.gapSent", gapsSent);
                DiagnosticMetrics.record("rss.tcp.weak.sackSent", sacksSent);
                DiagnosticMetrics.record("rss.tcp.weak.ackIgnoredNoPending", ignoredAcks);
                DiagnosticMetrics.record("rss.tcp.weak.untrackedTcpFrames", ordinaryTcpFrames);
                DiagnosticMetrics.record("rss.tcp.weak.udpTailProbes", udpTailProbes);
                DiagnosticMetrics.record("rss.tcp.weak.probeAckedBeforeTcp", probesAckedBeforeTcp);
                DiagnosticMetrics.record("rss.tcp.weak.probeHandedToTcp", probesHandedToTcp);
                DiagnosticMetrics.record("rss.tcp.weak.probeOutstandingAtClose", udpProbeOutstanding ? 1 : 0);
                DiagnosticMetrics.record("rss.tcp.weak.filteredDuplicates", filteredDuplicates);
                DiagnosticMetrics.record("rss.tcp.weak.probeGroupParityBytes", fec.probeGroupParityBytes());
            }
            DiagnosticMetrics.record("rss.tcp.adaptive.probes", probeCount);
            DiagnosticMetrics.record("rss.tcp.adaptive.transitions", toUdp, "to=udp");
            DiagnosticMetrics.record("rss.tcp.adaptive.transitions", toTcp, "to=tcp");
            DiagnosticMetrics.record("rss.tcp.adaptive.fallbacks", windowFallbacks, "reason=window");
            DiagnosticMetrics.record("rss.tcp.adaptive.fallbacks", timeoutFallbacks, "reason=timeout");
            DiagnosticMetrics.record("rss.tcp.adaptive.fallbacks", finFallbacks, "reason=fin");
            DiagnosticMetrics.record("rss.tcp.adaptive.tcpMillis", TimeUnit.NANOSECONDS.toMillis(
                    tcpNanos + (ready ? 0 : clock.getAsLong() - pathSince)));
            DiagnosticMetrics.record("rss.tcp.adaptive.peakPendingFrames", peakPending);
            DiagnosticMetrics.record("rss.tcp.adaptive.deliveredBytes", tcpDelivered, "path=tcp");
            DiagnosticMetrics.record("rss.tcp.adaptive.deliveredBytes", udpDelivered, "path=udp");
            DiagnosticMetrics.record("rss.tcp.adaptive.fecDataBytes", fec.dataBytes);
            DiagnosticMetrics.record("rss.tcp.adaptive.fecParityBytes", fec.parityBytes);
            DiagnosticMetrics.record("rss.tcp.adaptive.fecRecovered", fec.recoveredPackets());
        }
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
