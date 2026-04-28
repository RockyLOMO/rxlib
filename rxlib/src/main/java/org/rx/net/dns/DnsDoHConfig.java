package org.rx.net.dns;

import io.netty.handler.ssl.SslContext;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DnsDoHConfig {
    public static final int DEFAULT_MAX_DNS_MESSAGE_BYTES = 65535;
    public static final String DEFAULT_PATH = "/dns-query";

    boolean enabled;
    SslContext sslContext;
    String path = DEFAULT_PATH;
    boolean allowPlainHttp;
    int maxDnsMessageBytes = DEFAULT_MAX_DNS_MESSAGE_BYTES;

    public String getPath() {
        return path == null || path.isEmpty() ? DEFAULT_PATH : path;
    }

    public int getMaxDnsMessageBytes() {
        return maxDnsMessageBytes > 0 ? maxDnsMessageBytes : DEFAULT_MAX_DNS_MESSAGE_BYTES;
    }
}
