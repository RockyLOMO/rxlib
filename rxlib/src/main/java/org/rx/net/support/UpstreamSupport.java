package org.rx.net.support;

import lombok.*;
import org.rx.net.AuthenticEndpoint;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class UpstreamSupport {
    private final AuthenticEndpoint endpoint;
    private final SocksSupport support;
}
