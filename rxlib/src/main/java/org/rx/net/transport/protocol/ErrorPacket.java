package org.rx.net.transport.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class ErrorPacket implements Serializable {
    private static final long serialVersionUID = 7990939004009224185L;
    private final String errorMessage;
}
