package org.rx.net.socks;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.bean.FlagsEnum;

import java.io.Serializable;

@RequiredArgsConstructor
@Data
public class SocksConfig implements Serializable {
    private final int listenPort;
    private final FlagsEnum<TransportFlags> transportFlags;
    private int connectTimeoutMillis = 20000;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds;
    private int writeTimeoutSeconds;
}
