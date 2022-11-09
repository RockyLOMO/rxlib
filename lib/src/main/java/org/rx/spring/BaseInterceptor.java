package org.rx.spring;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.commons.lang3.BooleanUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.FlagsEnum;
import org.rx.bean.ProceedEventArgs;
import org.rx.core.*;
import org.rx.exception.TraceHandler;

import java.util.List;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.*;

public abstract class BaseInterceptor implements EventTarget<BaseInterceptor> {
    static final int MAX_FIELD_SIZE = 1024 * 4;
    static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();
    public final Delegate<BaseInterceptor, ProceedEventArgs> onProcessing = Delegate.create(),
            onProceed = Delegate.create(),
            onError = Delegate.create();

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags(EventFlags.QUIETLY);
    }

    protected final void enableTrace(String traceName) {
        if (traceName == null) {
            traceName = Constants.DEFAULT_TRACE_NAME;
        }
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        conf.setTraceName(traceName);
        ThreadPool.traceIdChangedHandler = p -> logCtx(conf.getTraceName(), p);
    }

    protected String startTrace(JoinPoint joinPoint, String parentTraceId) {
        return ThreadPool.startTrace(parentTraceId);
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        if (BooleanUtils.isTrue(idempotent.get())) {
            return joinPoint.proceed();
        }
        idempotent.set(Boolean.TRUE);
        String tn = RxConfig.INSTANCE.getThreadPool().getTraceName();
        if (tn != null) {
            logCtxIfAbsent(tn, startTrace(joinPoint, null));
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

            RxConfig conf = RxConfig.INSTANCE;
            eventArgs.setLogStrategy(conf.getLogStrategy());
            eventArgs.setLogTypeWhitelist(conf.getLogTypeWhitelist());
            try {
                eventArgs.proceed(() -> joinPoint.proceed(eventArgs.getParameters()));
                TraceHandler.INSTANCE.saveTrace(eventArgs.getDeclaringType(), signature.getName(), eventArgs.getParameters(), eventArgs.getElapsedNanos() / 1000L);
            } catch (Throwable e) {
                eventArgs.setError(e);
                raiseEvent(onError, eventArgs);
                if (eventArgs.getError() != null) {
                    throw eventArgs.getError();
                }
                eventArgs.setError(e);
            } finally {
                raiseEvent(onProceed, eventArgs);
                log(eventArgs, msg -> {
                    msg.appendLine("Call:\t%s", signature.getName());
                    msg.appendLine("Parameters:\t%s", jsonString(signature, eventArgs.getParameters()))
                            .appendLine("ReturnValue:\t%s\tElapsed=%s", jsonString(signature, eventArgs.getReturnValue()), Sys.formatNanosElapsed(eventArgs.getElapsedNanos()));
                    if (eventArgs.getError() != null) {
                        msg.appendLine("Error:\t%s", eventArgs.getError().getMessage());
                    }
                });
            }
            return eventArgs.getReturnValue();
        } finally {
            ThreadPool.endTrace();
            clearLogCtx();
            idempotent.remove();
        }
    }

    private String jsonString(Signature signature, Object... args) {
        if (Arrays.isEmpty(args)) {
            return "{}";
        }
        List<Object> list = Linq.from(args).select(p -> shortArg(signature, p)).toList();
        return toJsonString(list.size() == 1 ? list.get(0) : list);
    }

    protected final Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }

    protected Object shortArg(Signature signature, Object arg) {
        if (arg instanceof byte[]) {
            byte[] b = (byte[]) arg;
            if (b.length > MAX_FIELD_SIZE) {
                return "[BigBytes]";
            }
        }
        if (arg instanceof String) {
            String s = (String) arg;
            if (s.length() > MAX_FIELD_SIZE) {
                return "[BigString]";
            }
        }
        return arg;
    }
}
