package org.rx.net.rpc;

import lombok.Data;
import org.rx.net.MemoryMode;

import java.io.Serializable;

import static org.rx.core.Contract.CONFIG;

@Data
public class RpcServerConfig implements Serializable {
    private static final long serialVersionUID = 8065323693541916068L;
    private boolean tryEpoll = true;
    private int listenPort;
    private int workThread;
    private MemoryMode memoryMode;
    private int connectTimeoutMillis = CONFIG.getNetTimeoutMillis();
    private boolean enableSsl;
    private boolean enableCompress;
    private int capacity = 10000;
}
