package org.rx.net.dns;

import java.net.InetAddress;
import java.util.List;

public interface DnsResolveInterceptor {
    List<InetAddress> resolveHost(InetAddress srcIp, String host);
}
