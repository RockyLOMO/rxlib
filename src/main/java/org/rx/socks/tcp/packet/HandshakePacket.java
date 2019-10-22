package org.rx.socks.tcp.packet;

import lombok.Data;

import java.io.Serializable;

@Data
public class HandshakePacket implements Serializable {
    private String appId;
}
