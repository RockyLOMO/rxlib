package org.rx.net.transport.core;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.SocketConfig;

/**
 * Configuration for raw {@link io.netty.buffer.ByteBuf} UDP {@link DatagramTransport}.
 * Defaults to {@code reusePortBindCount = 1} so low-frequency control planes never inherit multi-bind SO_REUSEPORT.
 */
@Getter
@Setter
@ToString(callSuper = true)
public final class DatagramTransportConfig extends SocketConfig {
    private static final long serialVersionUID = 1L;

    private int bindPort;
    private int maxReceiveBytes = 65536;

    public DatagramTransportConfig() {
        setReusePortBindCount(1);
    }

    public DatagramTransportConfig(int bindPort) {
        this();
        this.bindPort = bindPort;
    }
}
