package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.support.UnresolvedEndpoint;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Map;

@Getter
@Setter
@ToString
public final class UdpRelayGroupOpenRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String clientId;
    private InetSocketAddress clientAddr;
    private UnresolvedEndpoint firstDestination;
    private int initialRelayCount = 1;
    private int minActiveRelays = 1;
    private int maxRelayCount = 1;
    private long idleTimeoutMillis;
    private boolean sharedDedupRequired;
    private Map<String, String> attributes;
}
