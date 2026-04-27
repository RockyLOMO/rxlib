package org.rx.net.transport.hybrid;

public final class HybridRoutePolicy {
    public HybridRoute select(HybridRouteState state, int encodedBytes, int udpThresholdBytes, HybridSendOptions options) {
        if (options != null && options.isForceTcp()) {
            return HybridRoute.TCP;
        }
        if (state == HybridRouteState.UDP_READY && encodedBytes >= 0 && encodedBytes <= udpThresholdBytes) {
            return HybridRoute.UDP;
        }
        return HybridRoute.TCP;
    }
}
