package org.rx.util.rss;

import org.rx.net.socks.SocksConfig;

import java.io.Serializable;

/** Per-user policy for the native RSS hop. Fields omitted in YAML inherit the selected profile. */
public final class RssTransport implements Serializable {
    private static final long serialVersionUID = 1L;
    static final RssTransport DEFAULT = new RssTransport();
    public String profile = "plain";
    public Integer fec, tcp, udp;

    boolean valid() {
        return ("plain".equals(profile) || "daily".equals(profile))
                && inRange(fec, 3) && inRange(tcp, 7) && inRange(udp, 3);
    }

    private static boolean inRange(Integer value, int max) {
        return value == null || value >= 0 && value <= max;
    }

    int fec() {
        return fec != null ? fec : ("daily".equals(profile) ? 3 : 0);
    }

    int tcp() {
        return tcp != null ? tcp : ("daily".equals(profile) ? 1 : 0);
    }

    int udpFeatures() {
        return (udp != null ? udp : 0) | ((fec() & 1) != 0 ? 4 : 0);
    }

    static int[] cipherPorts() {
        return new int[]{21, 23, 25, 53, 80, 110, 143, 389, 587, 1883, 8080};
    }

    static int[] alreadyEncryptedPorts() {
        return new int[]{22, 443, 465, 636, 853, 990, 993, 995, 8883};
    }

    static int[] udpCipherPorts() {
        return new int[]{53};
    }

    static boolean validPortLists(int[] cipher, int[] encrypted) {
        if (!SocksConfig.validCipherPorts(cipher) || !SocksConfig.validCipherPorts(encrypted)) {
            return false;
        }
        for (int port : encrypted) {
            for (int required : cipher) {
                if (port == required) {
                    return false;
                }
            }
        }
        return true;
    }
}
