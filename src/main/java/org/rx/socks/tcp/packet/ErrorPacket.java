package org.rx.socks.tcp.packet;

import lombok.Data;

import java.io.Serializable;

@Data
public class ErrorPacket implements Serializable {
    public static ErrorPacket error(String errorMessage) {
        ErrorPacket pack = new ErrorPacket();
        pack.setErrorMessage(errorMessage);
        return pack;
    }

    private String errorMessage;
}
