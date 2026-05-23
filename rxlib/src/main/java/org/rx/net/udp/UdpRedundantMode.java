package org.rx.net.udp;

/**
 * UDP redundant multi-send direction.
 */
public enum UdpRedundantMode {
    REQUEST_ONLY,
    RESPONSE_ONLY,
    BIDIRECTIONAL;

    public boolean allowRequest() {
        return this == REQUEST_ONLY || this == BIDIRECTIONAL;
    }

    public boolean allowResponse() {
        return this == RESPONSE_ONLY || this == BIDIRECTIONAL;
    }
}
