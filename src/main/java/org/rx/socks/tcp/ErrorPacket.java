package org.rx.socks.tcp;

import lombok.Data;

@Data
public class ErrorPacket implements SessionPacket {
    public static ErrorPacket error(String errorMessage) {
        ErrorPacket pack = new ErrorPacket();
        pack.setErrorMessage(errorMessage);
        return pack;
    }

    private String errorMessage;
}
