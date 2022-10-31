package org.rx.net.transport.protocol;

import lombok.Data;

import java.io.Serializable;

@Data
public class ErrorPacket implements Serializable {
    private static final long serialVersionUID = 7990939004009224185L;
    private final String errorMessage;
}
