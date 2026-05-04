package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@Setter
@ToString(exclude = "sessionSecret")
public class Udp2rawOpenResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private boolean supported;
    private String tunnelId;
    private long sessionHi;
    private long sessionLo;
    private byte[] sessionSecret;
    private InetSocketAddress udpEntryAddress;
    private long expireAtMillis;
    private Udp2rawCapabilities capabilities;
    private String errorCode;
    private String errorMessage;

    public static Udp2rawOpenResult unsupported() {
        Udp2rawOpenResult result = new Udp2rawOpenResult();
        result.supported = false;
        result.errorCode = "UNSUPPORTED";
        return result;
    }

    public static Udp2rawOpenResult fail(String errorCode, String errorMessage) {
        Udp2rawOpenResult result = new Udp2rawOpenResult();
        result.supported = true;
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        return result;
    }

    public static Udp2rawOpenResult success(String tunnelId, long sessionHi, long sessionLo,
            byte[] sessionSecret, InetSocketAddress udpEntryAddress,
            long expireAtMillis, Udp2rawCapabilities capabilities) {
        Udp2rawOpenResult result = new Udp2rawOpenResult();
        result.success = true;
        result.supported = true;
        result.tunnelId = tunnelId;
        result.sessionHi = sessionHi;
        result.sessionLo = sessionLo;
        result.sessionSecret = sessionSecret;
        result.udpEntryAddress = udpEntryAddress;
        result.expireAtMillis = expireAtMillis;
        result.capabilities = capabilities;
        return result;
    }
}
