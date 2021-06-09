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
    private int connectTimeoutMillis = 10000;
    private int trafficShapingInterval = 5000;
    private int readTimeoutSeconds = 120;
    private int writeTimeoutSeconds = 120;
}
