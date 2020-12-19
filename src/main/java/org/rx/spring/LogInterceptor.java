package org.rx.spring;

import com.google.common.base.Stopwatch;
import io.netty.util.concurrent.FastThreadLocal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.EventTarget;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.toJsonString;

@Aspect
@Component
public class LogInterceptor implements EventTarget<LogInterceptor> {
    static final FastThreadLocal<Map<String, Object>> metrics = new FastThreadLocal<Map<String, Object>>() {
        @Override
        protected Map<String, Object> initialValue() throws Exception {
            return new ConcurrentHashMap<>();
        }
    };

    public volatile BiConsumer<LogInterceptor, ProceedEventArgs> onProceed;

    @Around("@annotation(org.rx.annotation.EnableLogging) || @within(org.rx.annotation.EnableLogging)")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
        if (!log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        StringBuilder msg = new StringBuilder();
        boolean hasError = false;
        ProceedEventArgs eventArgs = new ProceedEventArgs(Thread.currentThread(), joinPoint);
        try {
            msg.appendLine("Call %s", signature.getName());
            Stopwatch watcher = Stopwatch.createStarted();
            Object p = joinPoint.getArgs();
            switch (joinPoint.getArgs().length) {
                case 0:
                    p = "NULL";
                    break;
                case 1:
                    p = joinPoint.getArgs()[0];
                    break;
            }
            msg.append("Parameters:\t\t%s", toJsonString(p));
            Map<String, Object> map = metrics.getIfExists();
            if (map != null) {
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    msg.append("\t%s=%s", entry.getKey(), toJsonString(entry.getValue()));
                }
            }
            Object r = joinPoint.proceed();
            msg.append("ReturnValue:\t%s", toJsonString(r));
            long ms = watcher.elapsed(TimeUnit.MILLISECONDS);
            msg.appendLine(String.format("\tElapsed=%sms", ms));
            eventArgs.setElapsedMillis(ms);
            return r;
        } catch (Exception e) {
            try {
                eventArgs.setException(e);
                return onException(eventArgs, msg);
            } catch (Throwable ie) {
                eventArgs.setException(ie);
                hasError = true;
                throw ie;
            }
        } finally {
            raiseEventAsync(onProceed, eventArgs);
            if (hasError) {
                log.error(msg.toString());
            } else {
                log.info(msg.toString());
            }
        }
    }

    protected Object onException(ProceedEventArgs eventArgs, StringBuilder msg) throws Throwable {
        msg.appendLine("Error:\t\t\t%s", eventArgs.getException().getMessage());
        throw eventArgs.getException();
    }

    protected Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }
}
