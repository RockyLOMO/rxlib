package org.rx.net.rpc.protocol;

import lombok.Data;

import java.io.Serializable;

@Data
public class HandshakePacket implements Serializable {
    private static final long serialVersionUID = -3218524051027224831L;
    private final int eventVersion;
}
