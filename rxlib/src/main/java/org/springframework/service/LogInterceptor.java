package org.springframework.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.annotation.EnableLog;
import org.rx.util.Validator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Executable;

@Aspect
@Component
public class LogInterceptor extends BaseInterceptor {
    @Around("@annotation(org.rx.annotation.EnableLog) || @within(org.rx.annotation.EnableLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        if (signature instanceof ConstructorSignature) {
            ConstructorSignature cs = (ConstructorSignature) signature;
            if (doValidate(cs.getConstructor())) {
                Validator.validateConstructor(cs.getConstructor(), joinPoint.getArgs(), joinPoint.getTarget());
            }
            return super.around(joinPoint);
        }

        MethodSignature ms = (MethodSignature) signature;
        if (doValidate(ms.getMethod())) {
            return Validator.validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), () -> super.around(joinPoint));
        }
        return super.around(joinPoint);
    }

    protected boolean doValidate(Executable r) {
        EnableLog a = r.getAnnotation(EnableLog.class);
        if (a == null) {
            a = r.getDeclaringClass().getAnnotation(EnableLog.class);
        }
        return a != null && a.doValidate();
    }
}
