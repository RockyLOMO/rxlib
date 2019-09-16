package org.rx.socks.tcp.impl;

import lombok.Data;
import org.rx.socks.tcp.SessionId;
import org.rx.socks.tcp.SessionPacket;

@Data
public class HandshakePacket implements SessionPacket {
    private SessionId sessionId;
}
