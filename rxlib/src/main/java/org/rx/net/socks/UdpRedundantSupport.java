package org.rx.net.socks;

import org.rx.net.udp.UdpRedundantConfig;
import org.rx.net.udp.UdpRedundantMode;

/**
 * SOCKS UDP 与 udp2raw 的 RDNT 方向判定。
 */
public final class UdpRedundantSupport {
    private UdpRedundantSupport() {
    }

    public static boolean isConfigured(SocksConfig config) {
        return config != null && (config.getUdpRedundantMultiplier() > 1
                || config.isUdpRedundantAdaptive()
                || config.hasUdpRedundantDestinationRules());
    }

    public static boolean allowSocksUdpRequest(SocksConfig config) {
        return socksMode(config).allowRequest();
    }

    public static boolean allowSocksUdpResponse(SocksConfig config) {
        return socksMode(config).allowResponse();
    }

    public static boolean allowUdp2rawRequest(SocksConfig config) {
        return modeOrDefault(config != null ? config.getUdp2rawRedundantMode() : null,
                UdpRedundantMode.BIDIRECTIONAL).allowRequest();
    }

    public static boolean allowUdp2rawResponse(SocksConfig config) {
        return modeOrDefault(config != null ? config.getUdp2rawRedundantMode() : null,
                UdpRedundantMode.BIDIRECTIONAL).allowResponse();
    }

    public static UdpRedundantConfig udp2rawConfigForRequest(UdpRedundantConfig config,
            UdpRedundantMode mode) {
        return modeOrDefault(mode, UdpRedundantMode.BIDIRECTIONAL).allowRequest() ? config : null;
    }

    public static UdpRedundantConfig udp2rawConfigForResponse(UdpRedundantConfig config,
            UdpRedundantMode mode) {
        return modeOrDefault(mode, UdpRedundantMode.BIDIRECTIONAL).allowResponse() ? config : null;
    }

    private static UdpRedundantMode socksMode(SocksConfig config) {
        UdpRedundantMode mode = config != null ? config.getSocksUdpRedundantMode() : null;
        return modeOrDefault(mode, UdpRedundantMode.REQUEST_ONLY);
    }

    private static UdpRedundantMode modeOrDefault(UdpRedundantMode mode, UdpRedundantMode defaultMode) {
        return mode != null ? mode : defaultMode;
    }
}
