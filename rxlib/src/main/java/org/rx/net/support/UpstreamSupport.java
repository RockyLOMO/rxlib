package org.rx.net.support;

import lombok.Data;
import org.rx.net.AuthenticEndpoint;

@Data
public class UpstreamSupport {
    private final AuthenticEndpoint endpoint;
    private final SocksSupport support;
}
