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
    private final Thread thread;
    private final JoinPoint joinPoint;
    private long elapsedMillis;
    private Throwable exception;
}
