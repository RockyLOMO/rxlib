package org.rx.net.http;

import org.springframework.core.NestedRuntimeException;

public class RestErrorException extends NestedRuntimeException {
    public RestErrorException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
