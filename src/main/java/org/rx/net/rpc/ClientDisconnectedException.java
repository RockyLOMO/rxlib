package org.rx.net.rpc;

import org.rx.core.exception.InvalidException;

public class ClientDisconnectedException extends InvalidException {
    public ClientDisconnectedException(Object clientId) {
        super("The client %s disconnected", clientId);
    }

    public ClientDisconnectedException(Throwable cause) {
        super("The client disconnected", cause);
    }
}
