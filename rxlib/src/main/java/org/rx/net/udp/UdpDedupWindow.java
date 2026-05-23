package org.rx.net.udp;

/**
 * 64-bit 序列号去重窗口，按 uint32 serial arithmetic 处理回绕。
 */
public class UdpDedupWindow {
    static final int WINDOW_SIZE = 64;

    private long highestSeq = -1;
    private long bitmap;
    private long lastAccessNanos = System.nanoTime();

    public boolean isDuplicate(long seq) {
        lastAccessNanos = System.nanoTime();
        if (highestSeq < 0) {
            highestSeq = seq;
            bitmap = 1L;
            return false;
        }

        long diff = sequenceDiff(seq, highestSeq);
        if (diff > 0) {
            if (diff >= WINDOW_SIZE) {
                bitmap = 1L;
            } else {
                bitmap <<= diff;
                bitmap |= 1L;
            }
            highestSeq = seq;
            return false;
        }
        if (diff == 0) {
            return true;
        }

        long age = -diff;
        if (age >= WINDOW_SIZE) {
            return true;
        }
        long mask = 1L << age;
        if ((bitmap & mask) != 0) {
            return true;
        }
        bitmap |= mask;
        return false;
    }

    long lastAccessNanos() {
        return lastAccessNanos;
    }

    private static long sequenceDiff(long newer, long older) {
        long diff = newer - older;
        if (diff > (1L << 31)) {
            diff -= (1L << 32);
        } else if (diff < -(1L << 31)) {
            diff += (1L << 32);
        }
        return diff;
    }
}
