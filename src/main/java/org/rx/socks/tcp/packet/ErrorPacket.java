package org.rx.socks.tcp.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@RequiredArgsConstructor
@Getter
public class ErrorPacket implements Serializable {
    private final String errorMessage;
}
