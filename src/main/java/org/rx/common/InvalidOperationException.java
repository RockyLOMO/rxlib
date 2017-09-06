package org.rx.common;

import com.google.common.base.Strings;

public class InvalidOperationException extends RuntimeException {
    public InvalidOperationException() {
        this((String) null);
    }

    public InvalidOperationException(String friendlyMessage) {
        this(friendlyMessage, null);
    }

    public InvalidOperationException(Throwable ex) {
        this(ex.getMessage(), ex);
    }

    public InvalidOperationException(String friendlyMessage, Throwable ex) {
        super(Strings.isNullOrEmpty(friendlyMessage) ? "网络异常，稍后再试。" : friendlyMessage, ex);
    }
}
