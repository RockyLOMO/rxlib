package org.rx.core.exception;

public class IgnoringException extends InvalidException {
    public IgnoringException(String format, Object... args) {
        super(format, args);
        level = ExceptionLevel.IGNORE;
    }
}
