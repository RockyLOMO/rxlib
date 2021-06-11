package org.rx.net.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.rx.bean.FlagsEnum;
import org.rx.net.SocketConfig;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class SocksConfig extends SocketConfig {
    private final int listenPort;
    private final FlagsEnum<TransportFlags> transportFlags;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds;
    private int writeTimeoutSeconds;
}
