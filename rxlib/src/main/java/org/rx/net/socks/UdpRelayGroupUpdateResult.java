package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Getter
@Setter
@ToString
public final class UdpRelayGroupUpdateResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private boolean supported;
    private String errorCode;
    private String errorMessage;
    private long expireAtMillis;
    private List<UdpRelayEndpoint> relays = Collections.emptyList();

    public static UdpRelayGroupUpdateResult unsupported() {
        UdpRelayGroupUpdateResult result = new UdpRelayGroupUpdateResult();
        result.supported = false;
        result.success = false;
        result.errorCode = "UNSUPPORTED";
        return result;
    }

    public static UdpRelayGroupUpdateResult fail(String errorCode, String errorMessage) {
        UdpRelayGroupUpdateResult result = new UdpRelayGroupUpdateResult();
        result.supported = true;
        result.success = false;
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        return result;
    }

    public static UdpRelayGroupUpdateResult success(long expireAtMillis, List<UdpRelayEndpoint> relays) {
        UdpRelayGroupUpdateResult result = new UdpRelayGroupUpdateResult();
        result.supported = true;
        result.success = true;
        result.expireAtMillis = expireAtMillis;
        result.relays = relays != null ? relays : Collections.<UdpRelayEndpoint>emptyList();
        return result;
    }
}
