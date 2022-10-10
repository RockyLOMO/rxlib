package org.rx.spring;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.commons.lang3.BooleanUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.FlagsEnum;
import org.rx.bean.ProceedEventArgs;
import org.rx.core.*;
import org.rx.exception.TraceHandler;
import org.rx.util.function.TripleFunc;

import java.util.List;

import static org.rx.core.App.*;
import static org.rx.core.Extends.as;

public abstract class BaseInterceptor implements EventTarget<BaseInterceptor> {
    static final int MAX_FIELD_SIZE = 1024 * 4;
    static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();
    public final Delegate<BaseInterceptor, ProceedEventArgs> onProcessing = Delegate.create(),
            onProceed = Delegate.create(),
            onError = Delegate.create();
    protected TripleFunc<Signature, Object, Object> argShortSelector;

    protected final void enableTrace() {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        conf.setTraceName("rx-traceId");
        ThreadPool.traceIdChangedHandler = p -> App.logExtra(conf.getTraceName(), p);
    }

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags(EventFlags.QUIETLY);
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        if (BooleanUtils.isTrue(idempotent.get())) {
            return joinPoint.proceed();
        }
        idempotent.set(Boolean.TRUE);
        String tn = RxConfig.INSTANCE.getThreadPool().getTraceName();
        if (tn != null) {
            App.logExtraIfAbsent(tn, ThreadPool.traceId(true));
        }
        try {
            Signature signature = joinPoint.getSignature();
            MethodSignature methodSignature = as(signature, MethodSignature.class);
            boolean isVoid = methodSignature == null || methodSignature.getReturnType().equals(void.class);
            ProceedEventArgs eventArgs = new ProceedEventArgs(signature.getDeclaringType(), joinPoint.getArgs(), isVoid);
            raiseEvent(onProcessing, eventArgs);
            if (eventArgs.isCancel()) {
                return joinPoint.proceed();
            }

            RxConfig rxConfig = RxConfig.INSTANCE;
            eventArgs.setLogStrategy(rxConfig.getLogStrategy());
            eventArgs.setLogTypeWhitelist(rxConfig.getLogTypeWhitelist());
            try {
                eventArgs.proceed(() -> joinPoint.proceed(eventArgs.getParameters()));
                TraceHandler.INSTANCE.saveTrace(eventArgs.getDeclaringType(), signature.getName(), eventArgs.getParameters(), eventArgs.getElapsedMicros());
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
                    msg.appendLine("Parameters:\t%s", jsonString(signature, eventArgs.getParameters()))
                            .appendLine("ReturnValue:\t%s\tElapsed=%s", jsonString(signature, eventArgs.getReturnValue()), App.formatElapsed(eventArgs.getElapsedMicros()));
                    if (eventArgs.getError() != null) {
                        msg.appendLine("Error:\t%s", eventArgs.getError().getMessage());
                    }
                });
            }
            return eventArgs.getReturnValue();
        } finally {
            App.clearLogExtras();
            idempotent.remove();
        }
    }

    private String jsonString(Signature signature, Object... args) {
        if (Arrays.isEmpty(args)) {
            return "{}";
        }
        List<Object> list = Linq.from(args).select(p -> {
            if (argShortSelector != null) {
                Object r = argShortSelector.invoke(signature, p);
                if (r != null) {
                    return r;
                }
            }
            if (p instanceof byte[]) {
                byte[] b = (byte[]) p;
                if (b.length > MAX_FIELD_SIZE) {
                    return "[BigBytes]";
                }
            }
            if (p instanceof String) {
                String s = (String) p;
                if (s.length() > MAX_FIELD_SIZE) {
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
