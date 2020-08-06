package org.rx.net.tcp.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@RequiredArgsConstructor
@Getter
public class HandshakePacket implements Serializable {
    private final String groupId;
}
