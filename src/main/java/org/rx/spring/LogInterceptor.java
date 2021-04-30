package org.rx.spring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.util.Validator;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LogInterceptor extends BaseInterceptor {
    //@within 对象级别
    //@annotation 方法级别
    @Around("@annotation(org.rx.annotation.EnableLogging) || @within(org.rx.annotation.EnableLogging)")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        if (signature instanceof ConstructorSignature) {
            ConstructorSignature cs = (ConstructorSignature) signature;
            Validator.validateConstructor(cs.getConstructor(), joinPoint.getTarget(), joinPoint.getArgs());
            return super.doAround(joinPoint);
        }
        MethodSignature ms = (MethodSignature) signature;
        return Validator.validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), () -> super.doAround(joinPoint));
    }
}
