package org.rx.net.http;

import org.rx.exception.InvalidException;

public class EmptyResponseException extends InvalidException {
    public EmptyResponseException(String format, Object... args) {
        super(format, args);
    }
}
