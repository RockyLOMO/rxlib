package org.rx.net.rpc.protocol;

import lombok.Getter;

import java.io.Serializable;

public final class PingMessage implements Serializable {
    private static final long serialVersionUID = 7964552443367680011L;
    @Getter
    final long timestamp = System.currentTimeMillis();
}
