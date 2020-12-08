package org.rx.net.rpc;

import lombok.Data;
import org.rx.net.MemoryMode;

import java.io.Serializable;
import java.net.InetSocketAddress;

import static org.rx.core.Contract.CONFIG;

@Data
public class RpcClientConfig implements Serializable {
    private static final long serialVersionUID = -4952694662640163676L;
    private boolean tryEpoll = true;
    private InetSocketAddress serverEndpoint;
    private MemoryMode memoryMode;
    private int connectTimeoutMillis = CONFIG.getNetTimeoutMillis();
    private boolean enableSsl;
    private boolean enableCompress;
}
