package org.rx.net.rpc;

import org.rx.core.exception.InvalidException;

public class RemotingException extends InvalidException {
    public RemotingException(String errorMessage) {
        super(errorMessage);
    }
}
