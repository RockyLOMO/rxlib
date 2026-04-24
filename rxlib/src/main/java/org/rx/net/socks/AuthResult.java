package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AuthResult {
    final SocksUser user;
    final TrafficUser trafficUser;
}
