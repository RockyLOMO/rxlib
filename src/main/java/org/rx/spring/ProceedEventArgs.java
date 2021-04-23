package org.rx.spring;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.aspectj.lang.JoinPoint;
import org.rx.core.EventArgs;

@Getter
@Setter
@RequiredArgsConstructor
public class ProceedEventArgs extends EventArgs {
    private final JoinPoint joinPoint;
    private final Object[] parameters;
    private LogWriteStrategy logStrategy;

    private Object returnValue;
    private long elapsedMillis;
    private Throwable exception;
}
