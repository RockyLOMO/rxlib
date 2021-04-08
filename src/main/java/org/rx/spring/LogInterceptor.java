package org.rx.spring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.util.Validator;
import org.springframework.stereotype.Component;

import javax.validation.Valid;
import java.lang.reflect.Executable;

@Aspect
@Component
public class LogInterceptor extends BaseInterceptor {
    @Around("@annotation(org.rx.annotation.EnableLogging) || @within(org.rx.annotation.EnableLogging)")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        Executable member;
        if (signature instanceof ConstructorSignature) {
            member = ((ConstructorSignature) signature).getConstructor();
        } else {
            member = ((MethodSignature) signature).getMethod();
        }

        Valid flag = member.getAnnotation(Valid.class);
        if (flag == null) {
            return super.doAround(joinPoint);
        }
        if (signature instanceof ConstructorSignature) {
            ConstructorSignature cs = (ConstructorSignature) signature;
            Validator.validateConstructor(cs.getConstructor(), joinPoint.getArgs(), true);
            return super.doAround(joinPoint);
        }
        MethodSignature ms = (MethodSignature) signature;
        return Validator.validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), true, () -> super.doAround(joinPoint));
    }
}
