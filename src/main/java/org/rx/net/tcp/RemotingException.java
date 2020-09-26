package org.rx.net.tcp;

import org.rx.core.exception.InvalidException;

public class RemotingException extends InvalidException {
    public RemotingException(String errorMessage) {
        super(errorMessage);
    }
}
