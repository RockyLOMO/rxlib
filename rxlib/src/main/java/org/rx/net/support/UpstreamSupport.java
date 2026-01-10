package org.rx.net.support;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;

@AllArgsConstructor
@Getter
@Setter
@ToString
public class UpstreamSupport {
    private AuthenticEndpoint endpoint;
    private SocksSupport support;
}
