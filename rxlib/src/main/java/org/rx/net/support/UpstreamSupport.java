package org.rx.net.support;

import lombok.*;
import org.rx.net.AuthenticEndpoint;

@AllArgsConstructor
@Getter
@Setter
@ToString
public class UpstreamSupport {
    private AuthenticEndpoint endpoint;
    private SocksSupport support;
}
