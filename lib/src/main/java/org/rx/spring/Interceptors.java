package org.rx.spring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.annotation.EnableLogging;
import org.rx.util.Validator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Executable;

//@within class level, @annotation method level
public class Interceptors {
    @Aspect
    @Component
    public static class LogInterceptor extends BaseInterceptor {
        @Around("@annotation(org.rx.annotation.EnableLogging) || @within(org.rx.annotation.EnableLogging)")
        public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
            Signature signature = joinPoint.getSignature();
            if (signature instanceof ConstructorSignature) {
                ConstructorSignature cs = (ConstructorSignature) signature;
                if (doValidate(cs.getConstructor())) {
                    Validator.validateConstructor(cs.getConstructor(), joinPoint.getTarget(), joinPoint.getArgs());
                }
                return super.doAround(joinPoint);
            }

            MethodSignature ms = (MethodSignature) signature;
            if (doValidate(ms.getMethod())) {
                return Validator.validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), () -> super.doAround(joinPoint));
            }
            return super.doAround(joinPoint);
        }

        boolean doValidate(Executable r) {
            EnableLogging a = r.getAnnotation(EnableLogging.class);
            return a != null && a.doValidate();
        }
    }

    @Aspect
    @Component
    public static class TraceInterceptor extends BaseInterceptor {
        public void setTraceName(String traceName) {
            super.enableTrace(traceName);
        }

        @Around("@annotation(org.rx.annotation.NewTrace) || @within(org.rx.annotation.NewTrace)")
        public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
            return super.doAround(joinPoint);
        }
    }
}
