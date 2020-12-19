package org.rx.spring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LogInterceptor extends BaseInterceptor {
    @Around("@annotation(org.rx.annotation.EnableLogging) || @within(org.rx.annotation.EnableLogging)")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        return super.doAround(joinPoint);
    }
}
