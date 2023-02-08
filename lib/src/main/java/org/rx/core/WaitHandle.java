package org.rx.core;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.concurrent.TimeoutException;

public interface WaitHandle {
    static TimeoutException newTimeoutException(String message, Throwable e) {
        StringBuilder buf = new StringBuilder(message);
        if (e != null) {
            buf.append("\n").append(ExceptionUtils.getStackTrace(e));
        }
        return new TimeoutException(buf.toString());
    }

    boolean await(long timeoutMillis);

    void signalAll();
}
