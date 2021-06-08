package org.rx.net.rpc;

import org.rx.core.exception.InvalidException;

public class ClientDisconnectedException extends InvalidException {
    public ClientDisconnectedException() {
        super("The client disconnected");
    }

    public ClientDisconnectedException(Throwable cause) {
        super("The client disconnected", cause);
    }
}
