package org.rx.net.udp;

import java.util.Arrays;

/** Event-loop confined circular bitmap. Check before authentication, mark only after it succeeds. */
public final class UdpSequenceWindow {
    // 16 groups of 16 shards, plus headroom for late data, parity and control packets.
    public static final int FEC_SIZE = 512;
    private final long[] bits;
    private final int mask;
    private long highest = -1;

    public UdpSequenceWindow(int size) {
        if (size < Long.SIZE || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("window size must be a power of two >= 64");
        }
        bits = new long[size / Long.SIZE];
        mask = size - 1;
    }

    public long highest() {
        return highest;
    }

    // Extend uint32 FEC serials relative to the newest observed serial, including rollover.
    boolean acceptSerial(int seq) {
        long extended = highest < 0 ? Integer.toUnsignedLong(seq) : highest + (seq - (int) highest);
        if (isDuplicate(extended)) return false;
        mark(extended);
        return true;
    }

    public boolean isDuplicate(long seq) {
        if (seq > highest) return false;
        return highest - seq > mask || (bits[((int) seq & mask) >>> 6] & (1L << seq)) != 0;
    }

    public void mark(long seq) {
        if (seq > highest) {
            // Bits for skipped sequence numbers may still be set from the previous turn.
            if (highest >= 0 && seq - highest > mask) {
                Arrays.fill(bits, 0L);
            } else if (highest >= 0 && seq != highest + 1) {
                int slot = (int) (highest + 1) & mask;
                int skipped = (int) (seq - highest - 1);
                while (skipped > 0) {
                    int offset = slot & 63;
                    int n = Math.min(skipped, Long.SIZE - offset);
                    bits[slot >>> 6] &= ~((-1L >>> (Long.SIZE - n)) << offset);
                    skipped -= n;
                    slot = (slot + n) & mask;
                }
            }
            highest = seq;
        }
        bits[((int) seq & mask) >>> 6] |= 1L << seq;
    }
}
