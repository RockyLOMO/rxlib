package org.rx.exception;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.helpers.MessageFormatter;

@Slf4j
public final class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    public static final ExceptionHandler INSTANCE = new ExceptionHandler();

    public void uncaughtException(String format, Object... args) {
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        if (e == null) {
            log.warn("ThrowableCandidate is null");
            return;
        }

        log.error(format, args);
    }

    public void uncaughtException(Throwable e) {
        uncaughtException(Thread.currentThread(), e);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("[{}] uncaught", t.getName(), e);
    }
}
