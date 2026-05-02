package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@Setter
@ToString
public final class UdpRelayEndpoint implements Serializable {
    private static final long serialVersionUID = 1L;

    private String relayId;
    private InetSocketAddress relayAddress;
    private int weight = 1;
    private long expireAtMillis;

    public UdpRelayEndpoint() {
    }

    public UdpRelayEndpoint(String relayId, InetSocketAddress relayAddress, int weight, long expireAtMillis) {
        this.relayId = relayId;
        this.relayAddress = relayAddress;
        this.weight = weight;
        this.expireAtMillis = expireAtMillis;
    }
}
