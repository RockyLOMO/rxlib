package org.rx.socks.tcp;

import lombok.Data;

@Data
public class HandshakePacket implements SessionPacket {
    private SessionId sessionId;
}
