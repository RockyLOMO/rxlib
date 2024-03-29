package org.rx.net.transport;

import org.rx.exception.InvalidException;

public class ClientDisconnectedException extends InvalidException {
    public ClientDisconnectedException(Object clientId) {
        super("The client {} disconnected", clientId);
    }

    public ClientDisconnectedException(Throwable cause) {
        super("The client disconnected", cause);
    }
}
