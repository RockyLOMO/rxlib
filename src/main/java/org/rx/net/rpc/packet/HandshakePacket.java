package org.rx.net.rpc.packet;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Data
@RequiredArgsConstructor
public class HandshakePacket implements Serializable {
    private static final long serialVersionUID = -3218524051027224831L;
    private final int eventVersion;
}
