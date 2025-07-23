package org.springframework.service;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.commons.lang3.BooleanUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.LogStrategy;
import org.rx.core.Reflects;
import org.rx.core.Sys;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.*;

public abstract class BaseInterceptor {
    static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();
    protected CallLogBuilder logBuilder = Sys.DEFAULT_LOG_BUILDER;
    protected LogStrategy logStrategy;

    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (BooleanUtils.isTrue(idempotent.get())) {
            return joinPoint.proceed();
        }
        idempotent.set(Boolean.TRUE);
        try {
            Signature signature = joinPoint.getSignature();
            MethodSignature methodSignature = as(signature, MethodSignature.class);
            Object[] args = joinPoint.getArgs();
            return Sys.callLog(signature.getDeclaringType(), signature.getName(), args, new ProceedFunc<Object>() {
                @Override
                public boolean isVoid() {
                    return methodSignature == null || methodSignature.getReturnType().equals(void.class);
                }

                @Override
                public Object invoke() throws Throwable {
                    return joinPoint.proceed(args);
                }
            }, logBuilder, logStrategy);
        } finally {
            clearLogCtx();
            idempotent.remove();
        }
    }

    protected final Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }
}
