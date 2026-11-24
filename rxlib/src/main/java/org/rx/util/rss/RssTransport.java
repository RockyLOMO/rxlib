package org.rx.util.rss;

import org.rx.net.socks.SocksConfig;
import org.rx.net.rss.udp.DailyUdpWire;

import java.io.Serializable;

/** Per-user policy for the native RSS hop. Fields omitted in YAML inherit the selected profile. */
public final class RssTransport implements Serializable {
    private static final long serialVersionUID = 1L;
    static final RssTransport DEFAULT = new RssTransport();
    public String profile = "plain";
    public Integer fec, tcp, udp;
    public Integer tcpCompressionLevel;

    boolean valid() {
        return ("plain".equals(profile) || "daily".equals(profile) || adaptiveProfile() != 0)
                && (tcpCompressionLevel == null || tcpCompressionLevel >= 1 && tcpCompressionLevel <= 9)
                && inRange(fec, 3) && inRange(tcp, 7) && inRange(udp, 3);
    }

    private static boolean inRange(Integer value, int max) {
        return value == null || value >= 0 && value <= max;
    }

    int adaptiveProfile() {
        if ("game".equals(profile)) return SocksConfig.ADAPTIVE_GAME;
        if ("weak".equals(profile)) return SocksConfig.ADAPTIVE_WEAK;
        return 0;
    }

    int compressionLevel() {
        return tcpCompressionLevel != null ? tcpCompressionLevel : RssSupport.TCP_COMPRESSION_LEVEL;
    }

    int fec() {
        if (fec != null) return fec;
        if ("game".equals(profile)) return 2;
        return "plain".equals(profile) ? 0 : 3;
    }

    int tcp() {
        return tcp != null ? tcp : ("plain".equals(profile) ? 0 : 1);
    }

    int udpFeatures() {
        int features = udp != null ? udp : 0;
        if ((fec() & 1) != 0) {
            return features | DailyUdpWire.FEC | ("weak".equals(profile) ? DailyUdpWire.WEAK : "game".equals(profile) ? DailyUdpWire.GAME : 0);
        }
        return features | ("game".equals(profile) ? DailyUdpWire.GAME : 0);
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
