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

    private String fakeHost;
    private String realEndpoint;

    public FakeEndpointRecovery(String fakeHost) {
        this.fakeHost = fakeHost;
    }
}
