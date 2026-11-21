package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One negotiated bidirectional link, confined to its owning event loop. Input buffers are borrowed.
 * Each callback takes ownership of the supplied reference. Call tick while the session is active
 * and close when the link is released. Peer validation and port selection belong to the caller.
 */
public final class UdpDailyFec implements AutoCloseable {
    static final int DATA = 0, SHARD = 1, PARITY = 2, REPORT = 3;
    public static final int MAX_OVERHEAD = 8; // parity: type, first sequence, count, XOR of lengths
    public static final int WIRE_HEADROOM = 20;
    public static final int MAX_PAYLOAD = 65507 - WIRE_HEADROOM - 5;
    private static final int MAX_SHARDS = 16, MIN_SHARDS = 4, MAX_SHARD_BYTES = 1500;
    private static final long FLUSH = TimeUnit.MILLISECONDS.toNanos(15);
    private static final long EXPIRE = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long REPORT_TIMEOUT = TimeUnit.SECONDS.toNanos(5);

    private final ByteBufAllocator alloc;
    private final LongSupplier clock;
    private final UdpDedupWindow delivered = new UdpDedupWindow();
    // At most one accumulator per group. Only XOR bytes are retained, never whole datagrams.
    private final Group sending = new Group();
    private final Group[] receiving = new Group[4];
    private int nextSeq, target, cleanWindows, reportSeq;
    private int highestRaw, nextReport;
    private long rawMask, lastReportTime;
    long sampledData, missingData, recoveredData, paritySent;
    private boolean observing, hasReport, closed;

    public UdpDailyFec(ByteBufAllocator allocator) {
        this(allocator, System::nanoTime);
    }

    public UdpDailyFec(ByteBufAllocator allocator, LongSupplier clock) {
        this.alloc = allocator;
        this.clock = clock;
    }

    public void write(ByteBuf payload, Consumer<ByteBuf> wire) {
        if (closed) {
            throw new IllegalStateException("FEC session closed");
        }
        int bytes = payload.readableBytes();
        if (bytes > MAX_PAYLOAD) {
            throw new IllegalArgumentException("daily UDP payload exceeds maximum datagram size");
        }
        long now = clock.getAsLong();
        expireSender(now, wire);
        int seq = nextSeq++;
        if (target == 0 || bytes > MAX_SHARD_BYTES) {
            flush(wire);
            wire.accept(newFrame(5 + bytes).writeByte(DATA).writeInt(seq)
                    .writeBytes(payload, payload.readerIndex(), bytes));
            return;
        }
        if (sending.xor == null) {
            sending.begin(seq, now, alloc, bytes);
        }
        int index = Integer.bitCount(sending.mask);
        sending.add(payload, payload.readerIndex(), bytes);
        sending.mask |= 1 << index;
        wire.accept(newFrame(6 + bytes).writeByte(SHARD).writeInt(seq).writeByte(index)
                .writeBytes(payload, payload.readerIndex(), bytes));
        if (index + 1 >= target) {
            flush(wire);
        }
    }

    public void read(ByteBuf frame, Consumer<ByteBuf> deliver, Consumer<ByteBuf> reply) {
        int pos = frame.readerIndex(), bytes = frame.readableBytes();
        if (closed || bytes < 5 || bytes > MAX_PAYLOAD + 5) {
            return;
        }
        int type = frame.getUnsignedByte(pos), seq = frame.getInt(pos + 1);
        long now = clock.getAsLong();
        if (type == REPORT) {
            if (bytes == 6) {
                acceptReport(seq, frame.getUnsignedByte(pos + 5), now);
            }
            return;
        }
        if (type == PARITY) {
            if (bytes < MAX_OVERHEAD || bytes > MAX_OVERHEAD + MAX_SHARD_BYTES) {
                return;
            }
            int count = frame.getUnsignedByte(pos + 5);
            if (count < MIN_SHARDS || count > MAX_SHARDS) {
                return;
            }
            Group group = group(seq, now, bytes - MAX_OVERHEAD);
            if (group.count != 0 || (group.mask >>> count) != 0) {
                return;
            }
            group.count = count;
            group.parityBytes = bytes - MAX_OVERHEAD;
            group.add(frame, pos + MAX_OVERHEAD, group.parityBytes);
            // add() included the parity packet length. Substitute the transmitted XOR of data lengths.
            group.lengthXor ^= group.parityBytes ^ frame.getUnsignedShort(pos + 6);
            recover(group, deliver);
            return;
        }
        boolean shard = type == SHARD;
        if (type != DATA && !shard || shard && (bytes < 6 || bytes - 6 > MAX_SHARD_BYTES)) {
            return;
        }
        int index = shard ? frame.getUnsignedByte(pos + 5) : 0;
        if (index >= MAX_SHARDS) {
            return;
        }
        observe(seq, reply);
        if (closed || delivered.isDuplicate(Integer.toUnsignedLong(seq))) {
            return;
        }
        int offset = pos + (shard ? 6 : 5), len = frame.writerIndex() - offset;
        Group group = shard ? group(seq - index, now, len) : null;
        if (group != null && (group.count == 0 || index < group.count)) {
            group.mask |= 1 << index;
            group.add(frame, offset, len);
        }
        deliver.accept(frame.retainedSlice(offset, len));
        if (group != null && !closed) {
            recover(group, deliver);
        }
    }

    // Report physical arrivals before recovery. Allow eight subsequent packets for reordering.
    private void observe(int seq, Consumer<ByteBuf> reply) {
        if (!observing) {
            observing = true;
            highestRaw = seq;
            nextReport = seq + 39;
            rawMask = 1L;
            return;
        }
        int advance = seq - highestRaw;
        if (advance <= 0) {
            if (advance > -64) {
                rawMask |= 1L << -advance;
            }
            return;
        }
        rawMask = (advance >= 64 ? 0 : rawMask << advance) | 1L;
        highestRaw = seq;
        if (seq - nextReport >= 0) {
            int missing = 32 - Integer.bitCount((int) (rawMask >>> 8));
            sampledData += 32;
            missingData += missing;
            nextReport = seq + 32;
            reply.accept(newFrame(6).writeByte(REPORT).writeInt(seq).writeByte(missing));
        }
    }

    private void acceptReport(int seq, int missing, long now) {
        if (missing > 32 || nextSeq - seq <= 0 || hasReport && seq - reportSeq <= 0) {
            return;
        }
        hasReport = true;
        reportSeq = seq;
        lastReportTime = now;
        if (missing > 0) {
            target = missing >= 4 ? 4 : missing >= 2 ? 8 : 16;
            cleanWindows = 0;
        } else if (target != 0 && ++cleanWindows >= 3) {
            target = 0;
            sending.clear();
        }
    }

    private Group group(int base, long now, int len) {
        int oldest = 0;
        for (int i = 0; i < receiving.length; i++) {
            Group g = receiving[i];
            if (g != null && g.xor != null && g.base == base && now - g.started < EXPIRE) {
                return g;
            }
            Group candidate = receiving[oldest];
            if (g == null || g.xor == null || candidate != null && candidate.xor != null && g.started < candidate.started) {
                oldest = i;
            }
        }
        Group g = receiving[oldest];
        if (g == null) {
            receiving[oldest] = g = new Group();
        }
        g.clear();
        g.begin(base, now, alloc, len);
        return g;
    }

    private void recover(Group g, Consumer<ByteBuf> deliver) {
        if (g.count == 0) {
            return;
        }
        int missing = ((1 << g.count) - 1) & ~g.mask;
        if (Integer.bitCount(missing) == 1 && g.lengthXor <= g.parityBytes
                && g.xor.readableBytes() == g.parityBytes) {
            int seq = g.base + Integer.numberOfTrailingZeros(missing);
            if (!delivered.isDuplicate(Integer.toUnsignedLong(seq))) {
                recoveredData++;
                ByteBuf payload = g.xor.retainedSlice(0, g.lengthXor);
                g.clear();
                deliver.accept(payload);
                return;
            }
        }
        if (Integer.bitCount(missing) <= 1) {
            g.clear();
        }
    }

    private ByteBuf newFrame(int len) {
        int capacity = WIRE_HEADROOM + len;
        return alloc.ioBuffer(capacity, capacity).setIndex(WIRE_HEADROOM, WIRE_HEADROOM);
    }

    /** Earliest parity flush or receive-buffer expiry. Idle sessions need no scheduled task. */
    public long nextDelayMillis() {
        long now = clock.getAsLong();
        long delay = sending.xor == null ? Long.MAX_VALUE : FLUSH - (now - sending.started);
        for (Group g : receiving) {
            if (g != null && g.xor != null) {
                delay = Math.min(delay, EXPIRE - (now - g.started));
            }
        }
        return delay == Long.MAX_VALUE ? -1 : Math.max(0, (delay + 999_999L) / 1_000_000L);
    }

    public void tick(Consumer<ByteBuf> wire) {
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        expireSender(now, wire);
        for (Group g : receiving) {
            if (g != null && g.xor != null && now - g.started >= EXPIRE) {
                g.clear();
            }
        }
    }

    private void expireSender(long now, Consumer<ByteBuf> wire) {
        if (target != 0 && now - lastReportTime >= REPORT_TIMEOUT) {
            target = 0;
            sending.clear();
        } else if (sending.xor != null && now - sending.started >= FLUSH) {
            flush(wire);
        }
    }

    private void flush(Consumer<ByteBuf> wire) {
        int count = Integer.bitCount(sending.mask);
        if (count >= MIN_SHARDS) {
            paritySent++;
            int len = sending.xor.readableBytes();
            ByteBuf frame = newFrame(MAX_OVERHEAD + len).writeByte(PARITY).writeInt(sending.base)
                    .writeByte(count).writeShort(sending.lengthXor).writeBytes(sending.xor, 0, len);
            sending.clear();
            wire.accept(frame);
        } else {
            sending.clear();
        }
    }

    @Override
    public void close() {
        closed = true;
        sending.clear();
        for (Group g : receiving) {
            if (g != null) {
                g.clear();
            }
        }
    }

    private static final class Group {
        ByteBuf xor;
        long started;
        int base, mask, count, parityBytes, lengthXor;

        void begin(int base, long now, ByteBufAllocator alloc, int len) {
            this.base = base;
            started = now;
            xor = alloc.ioBuffer(len, MAX_SHARD_BYTES);
        }

        void add(ByteBuf in, int offset, int len) {
            int extra = len - xor.writerIndex();
            if (extra > 0) {
                xor.writeZero(extra);
            }
            int i = 0;
            for (; i + 8 <= len; i += 8) {
                xor.setLong(i, xor.getLong(i) ^ in.getLong(offset + i));
            }
            for (; i < len; i++) {
                xor.setByte(i, xor.getByte(i) ^ in.getByte(offset + i));
            }
            lengthXor ^= len;
        }

        void clear() {
            if (xor != null) {
                xor.release();
                xor = null;
            }
            mask = count = lengthXor = 0;
        }
    }
}
