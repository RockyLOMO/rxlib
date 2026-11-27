package org.rx.net.rss;

/** UDP payload limits in bytes. Inputs are net RSS frame limits after removing carrier overhead. */
public final class RssUdpMtuBudget {
    public static final int MAX_DATAGRAM = 65507;
    public static final int MIN_NEGOTIATED = 256;

    public final int wireLimit;
    public final int payloadLimit;
    public final int fragmentUnitLimit;

    public RssUdpMtuBudget(int mtu, int overhead, int maxFragmentUnit) {
        validate(mtu);
        wireLimit = mtu == 0 ? MAX_DATAGRAM : mtu;
        payloadLimit = wireLimit - overhead;
        fragmentUnitLimit = Math.min(payloadLimit, maxFragmentUnit);
        if (overhead < 0 || fragmentUnitLimit <= 0) {
            throw new IllegalArgumentException("RSS UDP overhead exceeds frame budget");
        }
    }

    public static boolean valid(int mtu) {
        return mtu == 0 || mtu >= MIN_NEGOTIATED && mtu <= MAX_DATAGRAM;
    }

    public static int negotiate(int local, int peer) {
        validate(local);
        validate(peer);
        if (local == 0 || peer == 0) return Math.max(local, peer);
        return Math.min(local, peer);
    }

    private static void validate(int mtu) {
        if (!valid(mtu)) throw new IllegalArgumentException("Invalid RSS UDP payload MTU: " + mtu);
    }
}
