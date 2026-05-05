package org.rx.net.socks;

/**
 * Shared direction checks for SOCKS UDP and udp2raw redundant writes.
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
        return udp2rawMode(config != null ? config.getUdp2rawRedundantMode() : null).allowRequest();
    }

    public static boolean allowUdp2rawResponse(SocksConfig config) {
        return udp2rawMode(config != null ? config.getUdp2rawRedundantMode() : null).allowResponse();
    }

    public static UdpRedundantConfig udp2rawConfigForRequest(UdpRedundantConfig config,
            Udp2rawRedundantMode mode) {
        return udp2rawMode(mode).allowRequest() ? config : null;
    }

    public static UdpRedundantConfig udp2rawConfigForResponse(UdpRedundantConfig config,
            Udp2rawRedundantMode mode) {
        return udp2rawMode(mode).allowResponse() ? config : null;
    }

    public static UdpRedundantMode udp2rawMode(Udp2rawRedundantMode mode) {
        return mode != null ? mode.toCommonMode() : UdpRedundantMode.BIDIRECTIONAL;
    }

    private static UdpRedundantMode socksMode(SocksConfig config) {
        UdpRedundantMode mode = config != null ? config.getSocksUdpRedundantMode() : null;
        return mode != null ? mode : UdpRedundantMode.BIDIRECTIONAL;
    }
}
