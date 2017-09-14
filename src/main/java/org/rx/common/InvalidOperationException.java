package org.rx.common;

import com.google.common.base.Strings;
import org.springframework.core.NestedRuntimeException;

public class InvalidOperationException extends NestedRuntimeException {
    public InvalidOperationException() {
        this((String) null);
    }

    public InvalidOperationException(String friendlyMessage) {
        this(friendlyMessage, (Throwable) null);
    }

    public InvalidOperationException(Throwable ex) {
        this(ex.getMessage(), ex);
    }

    public InvalidOperationException(String friendlyMessage, Throwable ex) {
        super(Strings.isNullOrEmpty(friendlyMessage) ? "网络异常，稍后再试。" : friendlyMessage, ex);
    }

    public InvalidOperationException(String format, Object... args) {
        super(String.format(format, args));
    }
}
