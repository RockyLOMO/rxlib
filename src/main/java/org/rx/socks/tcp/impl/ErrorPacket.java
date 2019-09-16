package org.rx.socks.tcp.impl;

import lombok.Data;
import org.rx.socks.tcp.SessionPacket;

@Data
public class ErrorPacket implements SessionPacket {
    public static ErrorPacket error(String errorMessage) {
        ErrorPacket pack = new ErrorPacket();
        pack.setErrorMessage(errorMessage);
        return pack;
    }

    private String errorMessage;
}
