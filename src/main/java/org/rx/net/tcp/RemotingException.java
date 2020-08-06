package org.rx.net.tcp;

import org.rx.core.SystemException;

public class RemotingException extends SystemException {
    public RemotingException(String errorMessage) {
        super(errorMessage);
    }
}
