package org.rx.net.http.tunnel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.io.IOStream;

import java.io.Serializable;
import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
public class DataPack implements Serializable {
    private final String appName;
    private final String socksId;
    private final InetSocketAddress remoteEndpoint;
    @Setter
    private IOStream<?, ?> stream;
}
