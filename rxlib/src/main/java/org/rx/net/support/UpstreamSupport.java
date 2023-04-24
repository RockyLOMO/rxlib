package org.rx.net.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class UpstreamSupport {
    private final AuthenticEndpoint endpoint;
    private final SocksSupport support;
}
