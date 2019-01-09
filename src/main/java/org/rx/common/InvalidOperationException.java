package org.rx.common;

/**
 * 根据Exception来读取errorCode.yml的错误信息
 */
public class InvalidOperationException extends SystemException {
    public static SystemException friendly(String format, Object... args) {
        return new InvalidOperationException(null).setFriendlyMessage(format, args);
    }

    public InvalidOperationException(String format, Object... args) {
        super(String.format(format, args));
    }

    public InvalidOperationException(Throwable e) {
        super(e);
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
