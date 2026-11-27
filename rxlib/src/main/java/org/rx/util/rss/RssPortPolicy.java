package org.rx.util.rss;

import java.util.Arrays;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;

/** Immutable, protocol-specific codec selection. Port lookups run only while resolving a route. */
final class RssPortPolicy {
    enum Codec { DEFAULT, PROTECTED, BYPASS }

    private final int[] protectedPorts;
    private final int[] bypassPorts;

    RssPortPolicy(int[] protectedPorts, int[] bypassPorts) {
        this.protectedPorts = sortedDistinct(protectedPorts);
        this.bypassPorts = sortedDistinct(bypassPorts);
        if (Arrays.binarySearch(this.protectedPorts, SocksRpcContract.DNS_PORT) >= 0
                || Arrays.binarySearch(this.bypassPorts, SocksRpcContract.DNS_PORT) >= 0) {
            throw new IllegalArgumentException("DNS port 53 is reserved for RssDnsInterceptor");
        }
        for (int port : this.protectedPorts) {
            if (Arrays.binarySearch(this.bypassPorts, port) >= 0) {
                throw new IllegalArgumentException("RSS codec bypass overlaps protected port " + port);
            }
        }
    }

    Codec select(int port) {
        if (Arrays.binarySearch(protectedPorts, port) >= 0) return Codec.PROTECTED;
        return Arrays.binarySearch(bypassPorts, port) >= 0 ? Codec.BYPASS : Codec.DEFAULT;
    }

    private static int[] sortedDistinct(int[] ports) {
        if (!SocksConfig.validCipherPorts(ports)) throw new IllegalArgumentException("RSS ports must be in 1..65535");
        return Arrays.stream(ports).sorted().distinct().toArray();
    }
}
