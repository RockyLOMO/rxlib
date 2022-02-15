package org.rx.spring;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.commons.lang3.BooleanUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.FlagsEnum;
import org.rx.bean.ProceedEventArgs;
import org.rx.core.*;
import org.rx.util.function.TripleFunc;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.core.App.*;
import static org.rx.core.Extends.as;

public abstract class BaseInterceptor implements EventTarget<BaseInterceptor> {
    static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();
    public final Delegate<BaseInterceptor, ProceedEventArgs> onProcessing = Delegate.create(),
            onProceed = Delegate.create(),
            onError = Delegate.create();
    protected TripleFunc<Signature, Object, Object> argShortSelector;
    @Resource
    protected RxConfig rxConfig;

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags(EventFlags.QUIETLY);
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        if (BooleanUtils.isTrue(idempotent.get())) {
            return joinPoint.proceed();
        }
        idempotent.set(Boolean.TRUE);

        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        boolean isVoid = methodSignature == null || methodSignature.getReturnType().equals(void.class);
        ProceedEventArgs eventArgs = new ProceedEventArgs(signature.getDeclaringType(), joinPoint.getArgs(), isVoid);
        raiseEvent(onProcessing, eventArgs);
        if (eventArgs.isCancel()) {
            return joinPoint.proceed();
        }

        eventArgs.setLogStrategy(rxConfig.getLogStrategy());
        eventArgs.setLogTypeWhitelist(rxConfig.getLogTypeWhitelist());
        try {
            eventArgs.proceed(() -> joinPoint.proceed(eventArgs.getParameters()));
        } catch (Throwable e) {
            eventArgs.setError(e);
            raiseEvent(onError, eventArgs);
            if (eventArgs.getError() != null) {
                throw eventArgs.getError();
            }
            eventArgs.setError(e);
        } finally {
            raiseEvent(onProceed, eventArgs);
            App.log(eventArgs, msg -> {
                msg.appendLine("Call:\t%s", signature.getName());
                msg.appendLine("Parameters:\t%s", jsonString(signature, eventArgs.getParameters()));
                if (eventArgs.getError() != null) {
                    msg.appendLine("Error:\t%s", eventArgs.getError().getMessage());
                } else {
                    msg.appendLine("ReturnValue:\t%s\tElapsed=%sms", jsonString(signature, eventArgs.getReturnValue()), eventArgs.getElapsedMillis());
                }
            });
        }
        return eventArgs.getReturnValue();
    }

    private String jsonString(Signature signature, Object... args) {
        if (Arrays.isEmpty(args)) {
            return "{}";
        }
        List<Object> list = NQuery.of(args).select(p -> {
            if (argShortSelector != null) {
                Object r = argShortSelector.invoke(signature, p);
                if (r != null) {
                    return r;
                }
            }
            int maxLen = 1024 * 32;
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

    protected final Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }
}
