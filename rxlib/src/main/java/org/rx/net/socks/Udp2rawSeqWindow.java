package org.rx.net.socks;

final class Udp2rawSeqWindow {
    private boolean initialized;
    private long highest;
    private long bitmap;

    synchronized boolean checkAndMark(long seq) {
        if (!initialized) {
            initialized = true;
            highest = seq;
            bitmap = 1L;
            return true;
        }
        long diff = seq - highest;
        if (diff > 0) {
            bitmap = diff >= 64 ? 1L : (bitmap << diff) | 1L;
            highest = seq;
            return true;
        }
        long back = -diff;
        if (back >= 64) {
            return false;
        }
        long mask = 1L << back;
        if ((bitmap & mask) != 0) {
            return false;
        }
        bitmap |= mask;
        return true;
    }
}
