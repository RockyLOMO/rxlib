package org.rx.spring;

import com.google.common.base.Stopwatch;
import io.netty.util.concurrent.FastThreadLocal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.util.function.TripleFunc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.rx.core.App.as;
import static org.rx.core.App.toJsonString;

public abstract class BaseInterceptor implements EventTarget<BaseInterceptor> {
    static final FastThreadLocal<Map<String, Object>> metrics = new FastThreadLocal<Map<String, Object>>() {
        @Override
        protected Map<String, Object> initialValue() throws Exception {
            return new ConcurrentHashMap<>();
        }
    };

    public volatile BiConsumer<BaseInterceptor, ProceedEventArgs> onProcessing, onProceed;
    protected TripleFunc<Signature, Object, Object> argShortSelector;

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
        if (!log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        ProceedEventArgs eventArgs = new ProceedEventArgs(Thread.currentThread(), joinPoint);
        raiseEvent(onProcessing, eventArgs);
        if (eventArgs.isCancel()) {
            return joinPoint.proceed();
        }

        StringBuilder msg = new StringBuilder(App.getConfig().getBufferSize());
        boolean hasError = false;
        try {
            msg.appendLine("Call %s", signature.getName());
            Stopwatch watcher = Stopwatch.createStarted();
            msg.appendLine("Parameters:\t%s", toArgsString(signature, joinPoint.getArgs()));
            Object r = joinPoint.proceed();
            long ms = watcher.elapsed(TimeUnit.MILLISECONDS);
            msg.appendLine("ReturnValue:\t%s\tElapsed=%sms", toArgsString(signature, r), ms);
            eventArgs.setElapsedMillis(ms);
            Map<String, Object> map = metrics.getIfExists();
            if (map != null) {
                msg.append("metrics: ");
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    msg.append("\t%s=%s", entry.getKey(), toArgsString(signature, entry.getValue()));
                }
                msg.appendLine();
            }
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

    private String toArgsString(Signature signature, Object... args) {
        if (Arrays.isEmpty(args)) {
            return "NULL";
        }
        List<Object> list = NQuery.of(args).select(p -> {
            if (argShortSelector != null) {
                Object r = argShortSelector.invoke(signature, p);
                if (r != null) {
                    return r;
                }
            }
            int maxLen = 1024 * 512;
            if (p instanceof byte[]) {
                byte[] b = (byte[]) p;
                if (b.length > maxLen) {
                    return "[BigBytes]";
                }
            }
            if (p instanceof String) {
                String s = (String) p;
                if (Strings.length(s) > maxLen) {
                    return "[BigString]";
                }
            }
            return p;
        }).toList();
        return toJsonString(list.size() == 1 ? list.get(0) : list);
    }

    protected Object onException(ProceedEventArgs eventArgs, StringBuilder msg) throws Throwable {
        msg.appendLine("Error:\t%s", eventArgs.getException().getMessage());
        throw eventArgs.getException();
    }

    protected final Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }
}
