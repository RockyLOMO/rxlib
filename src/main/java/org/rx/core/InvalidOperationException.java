package org.rx.core;

public class InvalidOperationException extends SystemException {
    public InvalidOperationException(String format, Object... args) {
        super(String.format(format, args));
    }

    public InvalidOperationException(Throwable e, String format, Object... args) {
        super(e);
        setFriendlyMessage(format, args);
    }

    public <T extends Enum<T>> InvalidOperationException(T errorCode, Object... messageValues) {
        this(null, errorCode, messageValues);
    }

    public <T extends Enum<T>> InvalidOperationException(Throwable e, T errorCode, Object... messageValues) {
        super(e);
        setErrorCode(errorCode, messageValues);
    }

    public InvalidOperationException(Object[] messageValues, String errorName) {
        super(messageValues, errorName);
    }

    public InvalidOperationException(Object[] messageValues, Throwable ex) {
        super(messageValues, ex);
    }
}
