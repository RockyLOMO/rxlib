package org.rx.net.socks;

/**
 * udp2raw tunnel redundant multi-send direction.
 */
public enum Udp2rawRedundantMode {
    REQUEST_ONLY,
    RESPONSE_ONLY,
    BIDIRECTIONAL;

    UdpRedundantMode toCommonMode() {
        switch (this) {
            case REQUEST_ONLY:
                return UdpRedundantMode.REQUEST_ONLY;
            case RESPONSE_ONLY:
                return UdpRedundantMode.RESPONSE_ONLY;
            case BIDIRECTIONAL:
            default:
                return UdpRedundantMode.BIDIRECTIONAL;
        }
    }
}
