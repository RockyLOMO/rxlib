package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * 按目的地匹配 UDP 多倍发包倍率。多条规则时<strong>先配置者优先</strong>（列表顺序）。
 * <p>
 * {@code host} 支持：
 * <ul>
 *   <li>IPv4 / IPv6 字面量或主机名（解析为单一地址做精确匹配）</li>
 *   <li>仅 IPv4 CIDR，例如 {@code 10.0.0.0/24}</li>
 * </ul>
 * {@code port == 0} 表示任意端口；否则目的端口必须一致才命中。
 */
@Slf4j
@Getter
@Setter
public class UdpRedundantDestinationRule implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 目的主机：字面量、主机名，或 IPv4 CIDR（含 {@code /} 前缀长度）。
     */
    private String host = "";
    /**
     * 目的端口；0 = 不限制端口。
     */
    private int port = 0;
    /**
     * 命中时的倍率 [1, 5]。
     */
    private int multiplier = 2;

    private transient volatile Compiled compiled;
    private transient volatile boolean compileFailed;

    public void setMultiplier(int multiplier) {
        this.multiplier = Math.max(1, Math.min(5, multiplier));
    }

    /**
     * 是否匹配该 UDP 目的地址（不含 SOCKS 封装，为实际发往对端的 recipient）。
     */
    public boolean matches(InetSocketAddress destination) {
        if (destination == null || host == null || host.isEmpty()) {
            return false;
        }
        if (port != 0 && destination.getPort() != port) {
            return false;
        }
        Compiled c = compiled;
        if (compileFailed) {
            return false;
        }
        if (c == null) {
            synchronized (this) {
                if (!compileFailed && compiled == null) {
                    compiled = Compiled.tryCompile(host.trim());
                    if (compiled == null) {
                        compileFailed = true;
                    }
                }
                c = compiled;
            }
        }
        if (c == null) {
            return false;
        }
        return c.matches(destination.getAddress());
    }

    @Slf4j
    static final class Compiled {
        enum Kind {
            EXACT,
            IPV4_CIDR
        }

        private final Kind kind;
        private final InetAddress exact;
        private final byte[] network4;
        private final int prefixLen;

        private Compiled(InetAddress exact) {
            this.kind = Kind.EXACT;
            this.exact = exact;
            this.network4 = null;
            this.prefixLen = -1;
        }

        private Compiled(byte[] network4, int prefixLen) {
            this.kind = Kind.IPV4_CIDR;
            this.exact = null;
            this.network4 = network4;
            this.prefixLen = prefixLen;
        }

        static Compiled tryCompile(String hostSpec) {
            if (hostSpec.isEmpty()) {
                return null;
            }
            try {
                if (hostSpec.indexOf('/') >= 0) {
                    String[] parts = hostSpec.split("/", 2);
                    if (parts.length != 2) {
                        return null;
                    }
                    InetAddress net = InetAddress.getByName(parts[0].trim());
                    if (!(net instanceof Inet4Address)) {
                        log.warn("UDP redundant rule: IPv6 CIDR not supported, host={}", hostSpec);
                        return null;
                    }
                    int prefix = Integer.parseInt(parts[1].trim());
                    if (prefix < 0 || prefix > 32) {
                        log.warn("UDP redundant rule: invalid IPv4 prefix {}, host={}", prefix, hostSpec);
                        return null;
                    }
                    return new Compiled(net.getAddress(), prefix);
                }
                return new Compiled(InetAddress.getByName(hostSpec));
            } catch (UnknownHostException | NumberFormatException e) {
                log.warn("UDP redundant rule: bad host '{}'", hostSpec, e);
                return null;
            }
        }

        boolean matches(InetAddress addr) {
            if (addr == null) {
                return false;
            }
            if (kind == Kind.EXACT) {
                return exact.equals(addr);
            }
            if (!(addr instanceof Inet4Address)) {
                return false;
            }
            return matchIpv4Cidr(addr.getAddress(), network4, prefixLen);
        }

        private static boolean matchIpv4Cidr(byte[] address, byte[] network, int prefixLen) {
            if (address.length != 4 || network.length != 4) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            int remBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
