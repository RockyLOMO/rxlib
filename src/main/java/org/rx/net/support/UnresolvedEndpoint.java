package org.rx.net.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.net.InetSocketAddress;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class UnresolvedEndpoint implements Serializable {
    private String host;
    private int port;

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(host, port);
    }
}
