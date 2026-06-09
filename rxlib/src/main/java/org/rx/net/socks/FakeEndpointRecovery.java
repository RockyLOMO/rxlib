package org.rx.net.socks;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@NoArgsConstructor
public final class FakeEndpointRecovery implements Serializable {
    private static final long serialVersionUID = 1L;

    private long hash;
    private String fakeHost;
    private String realEndpoint;

    public FakeEndpointRecovery(long hash, String fakeHost) {
        this.hash = hash;
        this.fakeHost = fakeHost;
    }
}
