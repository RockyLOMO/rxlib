package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
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
    static final long DEFAULT_TIMEOUT_MILLIS = Math.max(100L,
            Long.getLong("rx.rss.udp.reassemblyTimeoutMillis", 750L));
    static final long DEFAULT_GLOBAL_BUDGET_BYTES = Math.max(1024L * 1024L,
            Long.getLong("rx.rss.udp.reassemblyGlobalBytes", 24L * 1024L * 1024L));
    static final AtomicLong GLOBAL_BUFFERED_BYTES = new AtomicLong();
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
        if (totalLen <= 0 || totalLen > MAX_DATAGRAM_BYTES) {
            return false;
        }
        int maxSlice = maxSliceBytes(maxUnitPayload);
        if (maxSlice <= 0) {
            return false;
        }
        int count = (totalLen + maxSlice - 1) / maxSlice;
        return count >= 1 && count <= MAX_FRAGMENTS;
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

            Assembly slot = findOrAllocate(msgId, fragCount, totalLen, now);
            if (slot == null) {
                return null;
            }
            if (slot.fragCount != fragCount || slot.totalLen != totalLen) {
                rxInvalid++;
                return null;
            }
            long bit = 1L << fragIndex;
            if ((slot.receivedMask & bit) != 0L) {
                rxDuplicates++;
                return null;
            }
            if (slot.receivedBytes + sliceLen > totalLen) {
                releaseSlot(slot);
                rxInvalid++;
                return null;
            }
            int charge = SLOT_OVERHEAD_BYTES + sliceLen;
            if (!reserveBytes(charge)) {
                rxBudgetRejected++;
                return null;
            }
            slot.parts[fragIndex] = frame.retainedSlice(sliceStart, sliceLen);
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
            ByteBuf assembled = alloc.ioBuffer(totalLen, totalLen);
            boolean ok = false;
            try {
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
                if (!ok) {
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

    @Override
    public void close() {
        closed = true;
        for (Assembly slot : assemblies) {
            if (slot != null && slot.active) {
                releaseSlot(slot);
            }
        }
    }

    private Assembly findOrAllocate(long msgId, int fragCount, int totalLen, long now) {
        int freeIdx = -1;
        for (int i = 0; i < assemblies.length; i++) {
            Assembly a = assemblies[i];
            if (a != null && a.active && a.msgId == msgId) {
                return a;
            }
            if (freeIdx < 0 && (a == null || !a.active)) {
                freeIdx = i;
            }
        }
        if (freeIdx < 0) {
            rxBudgetRejected++;
            return null;
        }
        Assembly slot = assemblies[freeIdx];
        if (slot == null) {
            assemblies[freeIdx] = slot = new Assembly();
        }
        slot.begin(msgId, fragCount, totalLen, now);
        return slot;
    }

    private boolean reserveBytes(int bytes) {
        if (sessionBytes + bytes > MAX_SESSION_BYTES) {
            return false;
        }
        for (;;) {
            long current = globalBudget.get();
            if (current + bytes > maxGlobalBytes) {
                return false;
            }
            if (globalBudget.compareAndSet(current, current + bytes)) {
                sessionBytes += bytes;
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
