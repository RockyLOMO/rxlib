package org.rx.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Container;
import org.slf4j.helpers.MessageFormatter;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    static {
        Container.register(ExceptionCodeHandler.class, new DefaultExceptionCodeHandler());
        Container.register(ExceptionHandler.class, new ExceptionHandler());
    }

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
