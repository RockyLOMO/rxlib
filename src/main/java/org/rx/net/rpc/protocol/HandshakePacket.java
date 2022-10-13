package org.rx.net.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HandshakePacket implements Serializable {
    private static final long serialVersionUID = -3218524051027224831L;
//    private final long handshakeTime = System.currentTimeMillis();
    private int eventVersion;
}
