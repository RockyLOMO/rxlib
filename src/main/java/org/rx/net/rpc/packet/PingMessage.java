package org.rx.net.rpc.packet;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

public final class PingMessage implements Serializable {
    @Getter
    final long timestamp = System.currentTimeMillis();
    @Getter
    @Setter
    long replyTimestamp;
}
