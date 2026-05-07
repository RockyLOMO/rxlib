package org.rx.net.socks;

import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;

/**
 * udp2raw 单隧道 MTU 探测状态。
 * <p>
 * 只维护少量标量，按低频控制帧收敛，业务包热路径只读取 {@link #currentMtu()}。
 */
@Slf4j
public final class Udp2rawMtuState {
    static final int DEFAULT_MTU = 1300;
    static final int MIN_MTU = 1200;
    static final int MAX_MTU = 1400;
    private static final int ADAPTIVE_MIN_MTU = 576;
    private static final int STEP_UP = 20;
    private static final int STEP_DOWN = 80;
    private static final int VERIFY_AFTER_UP_MISSES = 2;
    private static final long PROBE_INTERVAL_MILLIS = 15_000L;
    private static final long PROBE_RETRY_MILLIS = 5_000L;
    private static final long ACK_TIMEOUT_MILLIS = 2_000L;

    private final int minMtu;
    private final int maxMtu;
    private final String side;
    private volatile int currentMtu;
    private long nextSeq;
    private long pendingSeq;
    private int pendingMtu;
    private long pendingDeadlineMillis;
    private long nextProbeAtMillis;
    private boolean verified;
    private int upMisses;

    public Udp2rawMtuState(int initialMtu) {
        this(initialMtu, "client");
    }

    public Udp2rawMtuState(int initialMtu, String side) {
        this(initialMtu, ADAPTIVE_MIN_MTU, initialMtu > 0 ? initialMtu : MAX_MTU, side);
    }

    public Udp2rawMtuState(int initialMtu, int minMtu, int maxMtu) {
        this(initialMtu, minMtu, maxMtu, "client");
    }

    public Udp2rawMtuState(int initialMtu, int minMtu, int maxMtu, String side) {
        this.side = side != null && side.length() > 0 ? side : "unknown";
        if (initialMtu <= 0) {
            this.minMtu = 0;
            this.maxMtu = 0;
            this.currentMtu = 0;
            return;
        }
        // 协议级 MTU_PROBE 本身也有最小 datagram 长度，极小配置需 floor 到该值。
        int effectiveMax = maxMtu > 0
                ? Math.max(maxMtu, Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES)
                : MAX_MTU;
        int effectiveMin = Math.max(576, minMtu);
        if (effectiveMin > effectiveMax) {
            effectiveMin = effectiveMax;
        }
        this.minMtu = Math.max(Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES, effectiveMin);
        this.maxMtu = Math.max(this.minMtu, effectiveMax);
        this.currentMtu = clamp(initialMtu, this.minMtu, this.maxMtu);
        this.nextProbeAtMillis = System.currentTimeMillis() + 1_000L;
    }

    public synchronized Probe nextProbe(long now) {
        if (currentMtu <= 0) {
            return null;
        }
        if (pendingSeq != 0L) {
            if (now < pendingDeadlineMillis) {
                return null;
            }
            onTimeout(now);
        }
        if (now < nextProbeAtMillis) {
            return null;
        }
        if (currentMtu < Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES) {
            nextProbeAtMillis = now + PROBE_INTERVAL_MILLIS;
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                    "action=probe-too-small,mtuBucket=" + mtuBucket(currentMtu));
            return null;
        }

        int target = nextTargetMtu();
        long seq = ++nextSeq;
        pendingSeq = seq;
        pendingMtu = target;
        pendingDeadlineMillis = now + ACK_TIMEOUT_MILLIS;
        nextProbeAtMillis = now + PROBE_INTERVAL_MILLIS;
        DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                "action=send,mtuBucket=" + mtuBucket(target));
        return new Probe(seq, target);
    }

    public synchronized boolean ack(long seq, int acceptedMtu, long now) {
        if (seq == 0L || seq != pendingSeq) {
            return false;
        }
        int old = currentMtu;
        int accepted = acceptedMtu > 0 ? Math.min(acceptedMtu, pendingMtu) : pendingMtu;
        currentMtu = clamp(accepted, minMtu, maxMtu);
        verified = currentMtu >= pendingMtu;
        upMisses = 0;
        pendingSeq = 0L;
        pendingMtu = 0;
        pendingDeadlineMillis = 0L;
        nextProbeAtMillis = now + PROBE_INTERVAL_MILLIS;
        DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                "action=ack,mtuBucket=" + mtuBucket(currentMtu));
        DiagnosticMetrics.record("socks.udp2raw.mtu.current", currentMtu, "side=" + side);
        if (currentMtu > old) {
            log.info("udp2raw MTU adaptive UP: {} -> {}", old, currentMtu);
        } else if (currentMtu < old) {
            log.warn("udp2raw MTU adaptive DOWN by accepted size: {} -> {}", old, currentMtu);
        }
        return true;
    }

    public boolean ack(long seq, long now) {
        return ack(seq, 0, now);
    }

    public synchronized boolean cancelPendingProbe(long seq, long now) {
        if (seq == 0L || seq != pendingSeq) {
            return false;
        }
        pendingSeq = 0L;
        pendingMtu = 0;
        pendingDeadlineMillis = 0L;
        nextProbeAtMillis = Math.min(nextProbeAtMillis, now + PROBE_RETRY_MILLIS);
        DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D, "action=cancel,side=" + side);
        return true;
    }

    public int currentMtu() {
        return currentMtu;
    }

    public synchronized void onWriteMtuDrop(int datagramBytes, long now) {
        if (currentMtu <= 0) {
            return;
        }
        DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                "action=write-mtu-drop,mtuBucket=" + mtuBucket(datagramBytes));
        if (datagramBytes > currentMtu) {
            nextProbeAtMillis = Math.min(nextProbeAtMillis, now + PROBE_RETRY_MILLIS);
            return;
        }

        int old = currentMtu;
        currentMtu = Math.max(minMtu, currentMtu - STEP_DOWN);
        verified = false;
        upMisses = 0;
        pendingSeq = 0L;
        pendingMtu = 0;
        pendingDeadlineMillis = 0L;
        nextProbeAtMillis = now + PROBE_RETRY_MILLIS;
        if (currentMtu != old) {
            DiagnosticMetrics.record("socks.udp2raw.mtu.current", currentMtu, "side=" + side);
            log.warn("udp2raw MTU adaptive DOWN by write drop: {} -> {}", old, currentMtu);
        }
    }

    public synchronized long nextDelayMillis(long now) {
        if (currentMtu <= 0) {
            return 0L;
        }
        if (pendingSeq != 0L) {
            return Math.max(1L, pendingDeadlineMillis - now);
        }
        return Math.max(1L, nextProbeAtMillis - now);
    }

    private int nextTargetMtu() {
        if (!verified || upMisses >= VERIFY_AFTER_UP_MISSES || currentMtu >= maxMtu) {
            return currentMtu;
        }
        return Math.min(maxMtu, currentMtu + STEP_UP);
    }

    private void onTimeout(long now) {
        int old = currentMtu;
        boolean verifyProbe = pendingMtu <= currentMtu || !verified;
        if (verifyProbe) {
            currentMtu = Math.max(minMtu, currentMtu - STEP_DOWN);
            verified = false;
            upMisses = 0;
        } else {
            upMisses++;
        }
        DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                "action=timeout,mtuBucket=" + mtuBucket(pendingMtu));
        pendingSeq = 0L;
        pendingMtu = 0;
        pendingDeadlineMillis = 0L;
        nextProbeAtMillis = now + PROBE_RETRY_MILLIS;
        if (currentMtu != old) {
            DiagnosticMetrics.record("socks.udp2raw.mtu.current", currentMtu, "side=" + side);
            log.warn("udp2raw MTU adaptive DOWN: {} -> {}", old, currentMtu);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String mtuBucket(int mtu) {
        if (mtu <= 1200) {
            return "lte1200";
        }
        if (mtu <= 1300) {
            return "lte1300";
        }
        if (mtu <= 1400) {
            return "lte1400";
        }
        return "gt1400";
    }

    public static final class Probe {
        public final long seq;
        public final int mtu;

        Probe(long seq, int mtu) {
            this.seq = seq;
            this.mtu = mtu;
        }
    }
}
