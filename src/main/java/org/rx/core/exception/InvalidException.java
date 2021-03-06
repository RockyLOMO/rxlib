package org.rx.core.exception;

import lombok.Getter;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.bean.$;
import org.rx.core.Strings;
import org.springframework.core.NestedRuntimeException;

public class InvalidException extends NestedRuntimeException {
    private static final long serialVersionUID = -4772342123911366087L;

    public static RuntimeException sneaky(Throwable cause) {
        ExceptionUtils.rethrow(cause);
        return wrap(cause);
    }

    public static InvalidException wrap(Throwable cause) {
        if (cause == null) {
            return null;
        }
        if (cause instanceof InvalidException) {
            return (InvalidException) cause;
        }
        return new InvalidException(Strings.EMPTY, cause);
    }

    @Getter
    protected ExceptionLevel level;

    public InvalidException level(ExceptionLevel level) {
        this.level = level;
        return this;
    }

    public InvalidException(String format, Object... args) {
        this(format, args, null);
    }

    public InvalidException(String format, Object[] args, Throwable cause) {
        this(String.format(format, args), cause);
    }

    public InvalidException(String msg) {
        this(msg, (Throwable) null);
    }

    public InvalidException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public <T extends Throwable> boolean tryGet($<T> out, Class<T> exType) {
        if (out == null || exType == null) {
            return false;
        }
        if (exType.isInstance(this)) {
            out.v = (T) this;
            return true;
        }
        Throwable cause = this.getCause();
        if (cause == this) {
            return false;
        }
        if (cause instanceof InvalidException) {
            return ((InvalidException) cause).tryGet(out, exType);
        }

        while (cause != null) {
            if (exType.isInstance(cause)) {
                out.v = (T) cause;
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
