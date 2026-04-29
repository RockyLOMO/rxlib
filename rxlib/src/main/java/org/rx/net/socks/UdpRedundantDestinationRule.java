package org.rx.net.socks;

import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 按目的地匹配 UDP 多倍发包倍率。多条规则时<strong>先配置者优先</strong>（列表顺序）。
 * <p>
 * {@code host} 支持：
 * <ul>
 *   <li>IPv4 / IPv6 字面量，或未解析主机名（按 host 字符串精确匹配）</li>
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
        return c.matches(destination);
    }

    @Slf4j
    static final class Compiled {
        enum Kind {
            EXACT_ADDRESS,
            EXACT_HOST,
            IPV4_CIDR
        }

        private final Kind kind;
        private final InetAddress exact;
        private final String exactHost;
        private final byte[] network4;
        private final int prefixLen;

        private Compiled(InetAddress exact) {
            this.kind = Kind.EXACT_ADDRESS;
            this.exact = exact;
            this.exactHost = null;
            this.network4 = null;
            this.prefixLen = -1;
        }

        private Compiled(String exactHost) {
            this.kind = Kind.EXACT_HOST;
            this.exact = null;
            this.exactHost = exactHost;
            this.network4 = null;
            this.prefixLen = -1;
        }

        private Compiled(byte[] network4, int prefixLen) {
            this.kind = Kind.IPV4_CIDR;
            this.exact = null;
            this.exactHost = null;
            this.network4 = network4;
            this.prefixLen = prefixLen;
        }

        static Compiled tryCompile(String hostSpec) {
            if (hostSpec.isEmpty()) {
                return null;
            }
            try {
                int cidrIndex = hostSpec.indexOf('/');
                if (cidrIndex >= 0) {
                    String netSpec = normalizeHost(hostSpec.substring(0, cidrIndex).trim());
                    String prefixSpec = hostSpec.substring(cidrIndex + 1).trim();
                    if (netSpec.isEmpty() || prefixSpec.isEmpty()) {
                        return null;
                    }
                    byte[] network = NetUtil.createByteArrayFromIpAddressString(netSpec);
                    if (network == null || network.length != 4) {
                        log.warn("UDP redundant rule: IPv4 CIDR requires literal IPv4, host={}", hostSpec);
                        return null;
                    }
                    int prefix = Integer.parseInt(prefixSpec);
                    if (prefix < 0 || prefix > 32) {
                        log.warn("UDP redundant rule: invalid IPv4 prefix {}, host={}", prefix, hostSpec);
                        return null;
                    }
                    return new Compiled(network, prefix);
                }

                String normalized = normalizeHost(hostSpec);
                byte[] address = NetUtil.createByteArrayFromIpAddressString(normalized);
                if (address != null) {
                    return new Compiled(InetAddress.getByAddress(address));
                }
                return new Compiled(normalized.toLowerCase(Locale.ROOT));
            } catch (UnknownHostException | NumberFormatException e) {
                log.warn("UDP redundant rule: bad host '{}'", hostSpec, e);
                return null;
            }
        }

        boolean matches(InetSocketAddress destination) {
            if (destination == null) {
                return false;
            }
            if (kind == Kind.EXACT_HOST) {
                return exactHost.equals(normalizeHost(destination.getHostString()).toLowerCase(Locale.ROOT));
            }

            InetAddress addr = destination.getAddress();
            byte[] bytes = addr == null ? NetUtil.createByteArrayFromIpAddressString(normalizeHost(destination.getHostString())) : addr.getAddress();
            if (bytes == null) {
                return false;
            }
            if (kind == Kind.EXACT_ADDRESS) {
                if (addr == null) {
                    return java.util.Arrays.equals(exact.getAddress(), bytes);
                }
                return exact.equals(addr);
            }
            if (addr != null && !(addr instanceof Inet4Address)) {
                return false;
            }
            return matchIpv4Cidr(bytes, network4, prefixLen);
        }

        private static String normalizeHost(String host) {
            if (host == null) {
                return "";
            }
            String h = host.trim();
            int len = h.length();
            if (len > 1 && h.charAt(0) == '[' && h.charAt(len - 1) == ']') {
                return h.substring(1, len - 1);
            }
            return h;
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
