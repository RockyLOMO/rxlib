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
public final class UdpRelayGroupOpenResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private boolean supported;
    private String errorCode;
    private String errorMessage;
    private String groupId;
    private String token;
    private long expireAtMillis;
    private List<UdpRelayEndpoint> relays = Collections.emptyList();

    public static UdpRelayGroupOpenResult unsupported() {
        UdpRelayGroupOpenResult result = new UdpRelayGroupOpenResult();
        result.supported = false;
        result.success = false;
        result.errorCode = "UNSUPPORTED";
        return result;
    }

    public static UdpRelayGroupOpenResult fail(String errorCode, String errorMessage) {
        UdpRelayGroupOpenResult result = new UdpRelayGroupOpenResult();
        result.supported = true;
        result.success = false;
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        return result;
    }

    public static UdpRelayGroupOpenResult success(String groupId, String token, long expireAtMillis,
            List<UdpRelayEndpoint> relays) {
        UdpRelayGroupOpenResult result = new UdpRelayGroupOpenResult();
        result.supported = true;
        result.success = true;
        result.groupId = groupId;
        result.token = token;
        result.expireAtMillis = expireAtMillis;
        result.relays = relays != null ? relays : Collections.<UdpRelayEndpoint>emptyList();
        return result;
    }
}
