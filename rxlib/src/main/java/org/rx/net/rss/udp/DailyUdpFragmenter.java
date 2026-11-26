package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import org.rx.net.udp.UdpSequenceWindow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Negotiated, authenticated best-effort UDP fragmentation and reassembly for oversized RSS datagrams.
 * Event-loop confined per {@link DailyUdpWire} association; shares a process-wide byte budget.
 */
final class DailyUdpFragmenter implements AutoCloseable {
    static final int MAX_DATAGRAM_BYTES = 65507;
    static final int FRAG_MAGIC = 1;
    static final int PLAIN_HEADER_BYTES = 10; // magic(2) + msgId(4) + index(1) + count(1) + totalLen(2)
    private static final int AUTH_SEQ_BYTES = 8, AUTH_TAG_BYTES = 16;
    static final int AUTH_HEADER_BYTES = PLAIN_HEADER_BYTES + AUTH_SEQ_BYTES + AUTH_TAG_BYTES;
    static final int MAX_FRAGMENTS = 64;
    static final int MAX_CONCURRENT_ASSEMBLIES = 16;
    static final int MAX_SESSION_BYTES = 256 * 1024;
    private static final int SLOT_OVERHEAD_BYTES = 64;
    private static final int ASSEMBLY_BASE_OVERHEAD_BYTES = 128;
    private static final int COMPACT_SLACK_BYTES = 128;
    static final long DEFAULT_TIMEOUT_MILLIS = Math.max(100L,
            Long.getLong("rx.rss.udp.reassemblyTimeoutMillis", 750L));
    static final long DEFAULT_GLOBAL_BUDGET_BYTES = Math.max(1024L * 1024L,
            Long.getLong("rx.rss.udp.reassemblyGlobalBytes", 24L * 1024L * 1024L));
    static final AtomicLong GLOBAL_BUFFERED_BYTES = new AtomicLong();
    static final AtomicLong GLOBAL_PEAK_BYTES = new AtomicLong();
    private static final byte[] AUTH_LABEL = "RSS UDP FRAG MAC v1".getBytes(StandardCharsets.US_ASCII);

    private final boolean innerAuth;
    private final Mac txMac, rxMac;
    private final byte[] digest;
    private final UdpSequenceWindow authReplay;
    private final UdpSequenceWindow completedMessages = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
    private final Assembly[] assemblies = new Assembly[MAX_CONCURRENT_ASSEMBLIES];
    private final AtomicLong globalBudget;
    private final long maxGlobalBytes;
    private final long timeoutNanos;
    private long nextAuthSeq;
    private long nextMsgId;
    private int sessionBytes;
    private boolean closed;

    long txDatagrams, txFragments;
    long rxReassembled, rxTimeouts, rxDuplicates, rxInvalid, rxBudgetRejected;

    DailyUdpFragmenter(UUID token, int features, boolean server, byte[] fragAuthSecret) {
        this(token, features, server, fragAuthSecret, GLOBAL_BUFFERED_BYTES,
                DEFAULT_GLOBAL_BUDGET_BYTES, TimeUnit.MILLISECONDS.toNanos(DEFAULT_TIMEOUT_MILLIS));
    }

    DailyUdpFragmenter(UUID token, int features, boolean server, byte[] fragAuthSecret,
                       AtomicLong globalBudget, long maxGlobalBytes, long timeoutNanos) {
        this.globalBudget = globalBudget;
        this.maxGlobalBytes = maxGlobalBytes;
        this.timeoutNanos = timeoutNanos;
        this.innerAuth = fragAuthSecret != null;
        if (innerAuth) {
            if (fragAuthSecret.length != 32) {
                throw new IllegalArgumentException("fragment authentication secret must be 32 bytes");
            }
            try {
                Mac derive = Mac.getInstance("HmacSHA256");
                derive.init(new SecretKeySpec(fragAuthSecret, "HmacSHA256"));
                byte[] ctx = ByteBuffer.allocate(AUTH_LABEL.length + 18)
                        .put(AUTH_LABEL)
                        .putLong(token.getMostSignificantBits())
                        .putLong(token.getLeastSignificantBits())
                        .put((byte) features)
                        .put((byte) 0)
                        .array();
                Mac up = directionalMac(derive, ctx);
                ctx[ctx.length - 1] = 1;
                Mac down = directionalMac(derive, ctx);
                txMac = server ? down : up;
                rxMac = server ? up : down;
                digest = new byte[32];
                authReplay = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("UDP fragment HMAC unavailable", e);
            }
        } else {
            txMac = rxMac = null;
            digest = null;
            authReplay = null;
        }
    }

    private static Mac directionalMac(Mac derive, byte[] context) throws GeneralSecurityException {
        byte[] derived = derive.doFinal(context);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            return mac;
        } finally {
            java.util.Arrays.fill(derived, (byte) 0);
        }
    }

    static boolean isFragment(ByteBuf payload) {
        return payload.readableBytes() >= 2 && payload.getUnsignedShort(payload.readerIndex()) == FRAG_MAGIC;
    }

    int overheadBytes() {
        return innerAuth ? AUTH_HEADER_BYTES : PLAIN_HEADER_BYTES;
    }

    int maxSliceBytes(int maxUnitPayload) {
        return maxUnitPayload - overheadBytes();
    }

    boolean canFragment(int totalLen, int maxUnitPayload) {
        return fragmentCount(totalLen, maxUnitPayload) > 0;
    }

    int fragmentCount(int totalLen, int maxUnitPayload) {
        if (totalLen <= 0 || totalLen > MAX_DATAGRAM_BYTES) {
            return 0;
        }
        int maxSlice = maxSliceBytes(maxUnitPayload);
        if (maxSlice <= 0) {
            return 0;
        }
        int count = (totalLen + maxSlice - 1) / maxSlice;
        return count >= 1 && count <= MAX_FRAGMENTS ? count : 0;
    }

    void fragment(ByteBufAllocator alloc, ByteBuf data, int maxUnitPayload, FrameEmitter emitter) {
        int totalLen = data.readableBytes();
        int maxSlice = maxSliceBytes(maxUnitPayload);
        int count = (totalLen + maxSlice - 1) / maxSlice;
        if (maxSlice <= 0 || count < 1 || count > MAX_FRAGMENTS || totalLen > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("UDP payload cannot be fragmented within budget");
        }
        long msgSeq = ++nextMsgId;
        if (msgSeq <= 0) {
            msgSeq = nextMsgId = 1L;
        }
        int msgId = (int) msgSeq;
        int offset = data.readerIndex();
        int remaining = totalLen;
        txDatagrams++;
        txFragments += count;
        for (int idx = 0; idx < count; idx++) {
            int sliceLen = Math.min(maxSlice, remaining);
            int frameLen = overheadBytes() + sliceLen;
            ByteBuf frag = alloc.ioBuffer(DailyUdpWire.HEADER_BYTES + frameLen,
                    DailyUdpWire.HEADER_BYTES + frameLen + 16);
            frag.setIndex(DailyUdpWire.HEADER_BYTES, DailyUdpWire.HEADER_BYTES);
            boolean handedOff = false;
            try {
                int bodyStart = frag.writerIndex();
                frag.writeShort(FRAG_MAGIC);
                if (innerAuth) {
                    if (nextAuthSeq == Long.MAX_VALUE) {
                        throw new IllegalStateException("UDP fragment authentication sequence exhausted");
                    }
                    frag.writeLong(++nextAuthSeq);
                }
                frag.writeInt(msgId)
                        .writeByte(idx)
                        .writeByte(count)
                        .writeShort(totalLen)
                        .writeBytes(data, offset, sliceLen);
                if (innerAuth) {
                    sign(txMac, frag, bodyStart, frag.writerIndex() - bodyStart);
                    frag.writeBytes(digest, 0, AUTH_TAG_BYTES);
                }
                offset += sliceLen;
                remaining -= sliceLen;
                handedOff = true;
                emitter.emit(frag, idx == count - 1);
            } finally {
                if (!handedOff) {
                    frag.release();
                }
            }
        }
    }

    /** Takes ownership of {@code frame}. Returns a complete reassembled buffer when all fragments arrive, else null. */
    ByteBuf accept(ByteBuf frame, ByteBufAllocator alloc, long now) {
        try {
            if (closed) {
                return null;
            }
            expire(now);
            int pos = frame.readerIndex();
            int readable = frame.readableBytes();
            int minBytes = overheadBytes() + 1;
            if (readable < minBytes || frame.getUnsignedShort(pos) != FRAG_MAGIC) {
                rxInvalid++;
                return null;
            }
            int headerCursor = pos + 2;
            int sliceEnd = pos + readable;
            if (innerAuth) {
                long seq = frame.getLong(headerCursor);
                if (seq <= 0 || authReplay.isDuplicate(seq)) {
                    rxDuplicates++;
                    return null;
                }
                int signedLen = readable - AUTH_TAG_BYTES;
                sign(rxMac, frame, pos, signedLen);
                int diff = 0;
                int tagPos = pos + signedLen;
                for (int i = 0; i < AUTH_TAG_BYTES; i++) {
                    diff |= frame.getByte(tagPos + i) ^ digest[i];
                }
                if (diff != 0) {
                    rxInvalid++;
                    return null;
                }
                authReplay.mark(seq);
                headerCursor += AUTH_SEQ_BYTES;
                sliceEnd -= AUTH_TAG_BYTES;
            }

            long msgId = frame.getUnsignedInt(headerCursor);
            int fragIndex = frame.getUnsignedByte(headerCursor + 4);
            int fragCount = frame.getUnsignedByte(headerCursor + 5);
            int totalLen = frame.getUnsignedShort(headerCursor + 6);
            int sliceStart = headerCursor + 8;
            int sliceLen = sliceEnd - sliceStart;

            if (msgId == 0L || fragCount < 1 || fragCount > MAX_FRAGMENTS || fragIndex >= fragCount
                    || sliceLen <= 0 || totalLen < sliceLen || totalLen > MAX_DATAGRAM_BYTES
                    || (fragCount == 1 && totalLen != sliceLen)
                    || (fragCount > 1 && totalLen <= sliceLen)) {
                rxInvalid++;
                return null;
            }
            if (completedMessages.isDuplicate(msgId)) {
                rxDuplicates++;
                return null;
            }
            if (fragCount == 1) {
                completedMessages.mark(msgId);
                rxReassembled++;
                return frame.retainedSlice(sliceStart, sliceLen);
            }

            Assembly slot = null;
            int freeIdx = -1;
            for (int i = 0; i < assemblies.length; i++) {
                Assembly a = assemblies[i];
                if (a != null && a.active && a.msgId == msgId) {
                    slot = a;
                    break;
                }
                if (freeIdx < 0 && (a == null || !a.active)) {
                    freeIdx = i;
                }
            }
            long bit = 1L << fragIndex;
            if (slot != null) {
                if (slot.fragCount != fragCount || slot.totalLen != totalLen) {
                    rxInvalid++;
                    return null;
                }
                if ((slot.receivedMask & bit) != 0L) {
                    rxDuplicates++;
                    return null;
                }
                if (slot.receivedBytes + sliceLen > totalLen) {
                    releaseSlot(slot);
                    rxInvalid++;
                    return null;
                }
            } else if (freeIdx < 0) {
                rxBudgetRejected++;
                return null;
            }

            // Detach slices backed by oversized receive buffers (e.g. 64 KiB FixedRecvByteBufAllocator)
            // so pending fragments never pin ~50x more direct memory than budgeted.
            int rootCap = backingCapacity(frame);
            boolean compact = rootCap > sliceLen + COMPACT_SLACK_BYTES;
            int heldBytes = compact ? sliceLen : Math.max(sliceLen, rootCap);
            int charge = (slot == null ? assemblyOverhead(fragCount) : 0) + SLOT_OVERHEAD_BYTES + heldBytes;
            if (!reserveBytes(charge)) {
                rxBudgetRejected++;
                return null;
            }
            ByteBuf stored = null;
            try {
                if (compact) {
                    stored = alloc.ioBuffer(sliceLen, sliceLen);
                    stored.writeBytes(frame, sliceStart, sliceLen);
                } else {
                    stored = frame.retainedSlice(sliceStart, sliceLen);
                }
            } catch (Throwable t) {
                if (stored != null) {
                    stored.release();
                }
                releaseBytes(charge);
                throw t;
            }
            if (slot == null) {
                slot = assemblies[freeIdx];
                if (slot == null) {
                    assemblies[freeIdx] = slot = new Assembly();
                }
                slot.begin(msgId, fragCount, totalLen, now);
            }
            slot.parts[fragIndex] = stored;
            slot.receivedMask |= bit;
            slot.receivedBytes += sliceLen;
            slot.chargedBytes += charge;

            long expectedMask = fragCount == 64 ? -1L : (1L << fragCount) - 1L;
            if (slot.receivedMask != expectedMask) {
                return null;
            }
            if (slot.receivedBytes != totalLen) {
                releaseSlot(slot);
                rxInvalid++;
                return null;
            }
            ByteBuf assembled = null;
            boolean ok = false;
            try {
                assembled = alloc.ioBuffer(totalLen, totalLen);
                for (int i = 0; i < fragCount; i++) {
                    ByteBuf part = slot.parts[i];
                    assembled.writeBytes(part, part.readerIndex(), part.readableBytes());
                }
                completedMessages.mark(msgId);
                rxReassembled++;
                ok = true;
                return assembled;
            } finally {
                releaseSlot(slot);
                if (!ok && assembled != null) {
                    assembled.release();
                }
            }
        } finally {
            frame.release();
        }
    }

    long nextDelayMillis(long now) {
        long earliest = Long.MAX_VALUE;
        for (Assembly slot : assemblies) {
            if (slot != null && slot.active) {
                long remaining = timeoutNanos - (now - slot.startedNano);
                if (remaining < earliest) {
                    earliest = remaining;
                }
            }
        }
        return earliest == Long.MAX_VALUE ? -1L : Math.max(0L, (earliest + 999_999L) / 1_000_000L);
    }

    void expire(long now) {
        for (Assembly slot : assemblies) {
            if (slot != null && slot.active && now - slot.startedNano >= timeoutNanos) {
                releaseSlot(slot);
                rxTimeouts++;
            }
        }
    }

    int bufferedBytes() {
        return sessionBytes;
    }

    int activeAssemblies() {
        int count = 0;
        for (Assembly slot : assemblies) {
            if (slot != null && slot.active) {
                count++;
            }
        }
        return count;
    }

    static long globalBufferedBytes() {
        return GLOBAL_BUFFERED_BYTES.get();
    }

    static long globalPeakBytes() {
        return GLOBAL_PEAK_BYTES.get();
    }

    @Override
    public void close() {
        closed = true;
        for (Assembly slot : assemblies) {
            if (slot != null && slot.active) {
                releaseSlot(slot);
            }
        }
    }

    private static int assemblyOverhead(int fragCount) {
        return ASSEMBLY_BASE_OVERHEAD_BYTES + (fragCount << 3);
    }

    private static int backingCapacity(ByteBuf buf) {
        if (buf instanceof CompositeByteBuf) {
            return Integer.MAX_VALUE;
        }
        ByteBuf root = buf;
        while (root.unwrap() != null) {
            root = root.unwrap();
            if (root instanceof CompositeByteBuf) {
                return Integer.MAX_VALUE;
            }
        }
        return root.capacity();
    }

    private boolean reserveBytes(int bytes) {
        if (sessionBytes + bytes > MAX_SESSION_BYTES) {
            return false;
        }
        for (;;) {
            long current = globalBudget.get();
            long next = current + bytes;
            if (next > maxGlobalBytes) {
                return false;
            }
            if (globalBudget.compareAndSet(current, next)) {
                sessionBytes += bytes;
                if (globalBudget == GLOBAL_BUFFERED_BYTES) {
                    long peak = GLOBAL_PEAK_BYTES.get();
                    while (next > peak && !GLOBAL_PEAK_BYTES.compareAndSet(peak, next)) {
                        peak = GLOBAL_PEAK_BYTES.get();
                    }
                }
                return true;
            }
        }
    }

    private void releaseBytes(int bytes) {
        if (bytes <= 0) {
            return;
        }
        sessionBytes = Math.max(0, sessionBytes - bytes);
        globalBudget.addAndGet(-bytes);
    }

    private void releaseSlot(Assembly slot) {
        if (!slot.active) {
            return;
        }
        slot.active = false;
        if (slot.parts != null) {
            for (int i = 0; i < slot.fragCount; i++) {
                ByteBuf p = slot.parts[i];
                if (p != null) {
                    slot.parts[i] = null;
                    p.release();
                }
            }
        }
        int charged = slot.chargedBytes;
        slot.chargedBytes = 0;
        slot.receivedBytes = 0;
        slot.receivedMask = 0L;
        releaseBytes(charged);
    }

    private void sign(Mac mac, ByteBuf buf, int pos, int len) {
        mac.update(buf.nioBuffer(pos, len));
        try {
            mac.doFinal(digest, 0);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    interface FrameEmitter {
        void emit(ByteBuf frame, boolean last);
    }

    private static final class Assembly {
        long msgId;
        long startedNano;
        long receivedMask;
        int fragCount;
        int totalLen;
        int receivedBytes;
        int chargedBytes;
        ByteBuf[] parts;
        boolean active;

        void begin(long msgId, int fragCount, int totalLen, long now) {
            this.msgId = msgId;
            this.fragCount = fragCount;
            this.totalLen = totalLen;
            this.startedNano = now;
            this.receivedMask = 0L;
            this.receivedBytes = 0;
            this.chargedBytes = 0;
            if (parts == null || parts.length < fragCount) {
                parts = new ByteBuf[fragCount];
            }
            this.active = true;
        }
    }
}
