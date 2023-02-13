package org.rx.net.rpc;

import org.rx.exception.InvalidException;

public class RemotingException extends InvalidException {
    public RemotingException(String errorMessage) {
        super(errorMessage);
    }
}
