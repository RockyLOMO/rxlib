package org.rx.net.socks;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.bean.FlagsEnum;

import java.io.Serializable;

@RequiredArgsConstructor
@Data
public class SslDirectConfig implements Serializable {
    private final int listenPort;
    private final FlagsEnum<TransportFlags> transportFlags;
}
