package org.rx.net.rpc.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@Getter
@RequiredArgsConstructor
public class ErrorPacket implements Serializable {
    private static final long serialVersionUID = 7990939004009224185L;
    private final String errorMessage;
}
