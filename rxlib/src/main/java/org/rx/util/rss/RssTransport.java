package org.rx.util.rss;

import org.rx.net.socks.SocksConfig;
import org.rx.net.rss.profile.RssProfile;
import org.rx.net.rss.udp.RssDatagramHandler;

import java.io.Serializable;

/** Per-user policy for the native RSS hop. Fields omitted in YAML inherit the selected profile. */
public final class RssTransport implements Serializable {
    private static final long serialVersionUID = 1L;
    static final RssTransport DEFAULT = new RssTransport();
    public String profile = "plain";
    public Integer fec, tcp, udp;
    public Integer tcpCompressionLevel;
    public Boolean udpHop;
    public Integer udpMtu;
    public String handshakeMode;
    public String securityMode;
    public RssClientConf.Egress egress;

    boolean udpHop() {
        return RssProfile.named(profile) != RssProfile.PLAIN && !Boolean.FALSE.equals(udpHop);
    }

    static boolean validUdpMtu(Integer mtu) {
        return mtu == null || mtu == 0 || (mtu >= 256 && mtu <= 65507);
    }

    boolean valid() {
        return valid(null);
    }

    boolean valid(org.rx.net.EgressConfig globalEgress) {
        RssProfile selected = RssProfile.named(profile);
        if (selected == null || fec != null && fec != selected.fecMask) {
            return false;
        }
        if (handshakeMode != null) {
            try {
                org.rx.net.HandshakeMode.parse(handshakeMode);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (securityMode != null) {
            try {
                org.rx.net.SecurityMode.parse(securityMode);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (egress != null) {
            try {
                egress.toClientEgressConfig(globalEgress);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        boolean plain = selected == RssProfile.PLAIN;
        return (plain ? !Boolean.TRUE.equals(udpHop) && inRange(tcp, 3) : tcp == null || tcp == selected.tcpMask)
                && (tcpCompressionLevel == null || tcpCompressionLevel >= 1 && tcpCompressionLevel <= 9)
                && validUdpMtu(udpMtu)
                && inRange(udp, plain ? 0 : RssDatagramHandler.LZ4);
    }

    org.rx.net.HandshakeMode resolveHandshakeMode(org.rx.net.HandshakeMode fallback) {
        if (handshakeMode != null && !handshakeMode.trim().isEmpty()) {
            return org.rx.net.HandshakeMode.parse(handshakeMode);
        }
        return fallback == null ? org.rx.net.HandshakeMode.LIGHTWEIGHT : fallback;
    }

    org.rx.net.SecurityMode resolveSecurityMode(org.rx.net.SecurityMode fallback) {
        if (securityMode != null && !securityMode.trim().isEmpty()) {
            return org.rx.net.SecurityMode.parse(securityMode);
        }
        return fallback == null ? org.rx.net.SecurityMode.FAST : fallback;
    }

    org.rx.net.EgressConfig tcpEgressConfig(org.rx.net.EgressConfig fallback) {
        org.rx.net.EgressConfig base = fallback == null ? org.rx.net.EgressConfig.none() : fallback;
        return egress == null ? base.copy() : egress.toClientEgressConfig(base);
    }

    org.rx.net.EgressCodec tcpEgressCodec(org.rx.net.EgressCodec fallback) {
        org.rx.net.EgressConfig base = org.rx.net.EgressConfig.client(fallback == null ? org.rx.net.EgressCodec.NONE : fallback);
        return egress == null ? base.getTcpCodec() : egress.toClientEgressConfig(base).getTcpCodec();
    }

    private static boolean inRange(Integer value, int max) {
        return value == null || value >= 0 && value <= max;
    }

    int adaptiveProfile() {
        return RssProfile.named(profile).adaptiveId;
    }

    int compressionLevel() {
        return tcpCompressionLevel != null ? tcpCompressionLevel : RssSupport.TCP_COMPRESSION_LEVEL;
    }

    int fec() {
        return fec != null ? fec : RssProfile.named(profile).fecMask;
    }

    int tcp() {
        return tcp != null ? tcp : RssProfile.named(profile).tcpMask;
    }

    int udpFeatures() {
        RssProfile policy = RssProfile.named(profile);
        if (policy == RssProfile.PLAIN) {
            return 0;
        }
        int compression = udp == null ? RssDatagramHandler.LZ4 : udp;
        switch (policy) {
            case GAME: return compression | RssDatagramHandler.GAME;
            case WEAK: return compression | RssDatagramHandler.FEC | RssDatagramHandler.WEAK;
            default: return compression | RssDatagramHandler.FEC;
        }
    }

    static int[] cipherPorts() {
        return new int[]{21, 23, 25, 80, 110, 143, 389, 587, 1883, 8080};
    }

    static int[] alreadyEncryptedPorts() {
        return new int[]{22, 443, 465, 636, 853, 990, 993, 995, 8883};
    }

    static int[] udpCipherPorts() {
        return new int[0];
    }

    static boolean validPortLists(int[] cipher, int[] encrypted) {
        if (!SocksConfig.validCipherPorts(cipher) || !SocksConfig.validCipherPorts(encrypted)) {
            return false;
        }
        for (int port : cipher) {
            if (port == org.rx.net.socks.SocksRpcContract.DNS_PORT) {
                return false;
            }
        }
        for (int port : encrypted) {
            if (port == org.rx.net.socks.SocksRpcContract.DNS_PORT) {
                return false;
            }
            for (int required : cipher) {
                if (port == required) {
                    return false;
                }
            }
        }
        return true;
    }
}
