package org.rx.spring;

import com.google.common.base.Stopwatch;
import io.netty.util.concurrent.FastThreadLocal;
import org.apache.commons.collections4.CollectionUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.FlagsEnum;
import org.rx.bean.RxConfig;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.util.function.TripleFunc;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

public abstract class BaseInterceptor implements EventTarget<BaseInterceptor> {
    static final FastThreadLocal<Map<String, Object>> metrics = new FastThreadLocal<Map<String, Object>>() {
        @Override
        protected Map<String, Object> initialValue() throws Exception {
            return new ConcurrentHashMap<>();
        }
    };

    public volatile BiConsumer<BaseInterceptor, ProceedEventArgs> onProcessing, onProceed, onError;
    protected TripleFunc<Signature, Object, Object> argShortSelector;
    @Resource
    protected RxConfig rxConfig;

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.flags(EventFlags.Quietly);
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        ProceedEventArgs eventArgs = new ProceedEventArgs(joinPoint, joinPoint.getArgs());
        raiseEvent(onProcessing, eventArgs);
        if (eventArgs.isCancel()) {
            return joinPoint.proceed();
        }

        eventArgs.setLogStrategy(LogWriteStrategy.WriteOnError);
        try {
            Stopwatch watcher = Stopwatch.createStarted();
            eventArgs.setReturnValue(joinPoint.proceed(eventArgs.getParameters()));
            eventArgs.setElapsedMillis(watcher.elapsed(TimeUnit.MILLISECONDS));
        } catch (Throwable e) {
            eventArgs.setException(e);
            raiseEvent(onError, eventArgs);
            if (eventArgs.getException() != null) {
                throw eventArgs.getException();
            }
            eventArgs.setException(e);
        } finally {
            raiseEvent(onProceed, eventArgs);
            boolean doWrite = false;
            switch (isNull(eventArgs.getLogStrategy(), LogWriteStrategy.WriteOnError)) {
                case WriteOnError:
                    if (eventArgs.getException() != null) {
                        doWrite = true;
                    }
                    break;
                case Always:
                    doWrite = true;
                    break;
            }
            if (doWrite) {
                List<String> whitelist = rxConfig.getLogTypeWhitelist();
                if (!CollectionUtils.isEmpty(whitelist)) {
                    doWrite = NQuery.of(whitelist).any(p -> signature.getDeclaringTypeName().contains(p));
                }
            }
            if (doWrite) {
                org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
                String msg = formatMessage(signature, eventArgs);
                if (eventArgs.getException() != null) {
                    log.error(msg, eventArgs.getException());
                } else {
                    log.info(msg);
                }
            }
        }
        return eventArgs.getReturnValue();
    }

    private String formatMessage(Signature signature, ProceedEventArgs eventArgs) {
        StringBuilder msg = new StringBuilder(App.getConfig().getBufferSize());
        msg.appendLine("Call %s", signature.getName());
        msg.appendLine("Parameters:\t%s", jsonString(signature, eventArgs.getParameters()));
        if (eventArgs.getException() != null) {
            msg.appendLine("Error:\t%s", eventArgs.getException().getMessage());
        } else {
            msg.appendLine("ReturnValue:\t%s\tElapsed=%sms", jsonString(signature, eventArgs.getReturnValue()), eventArgs.getElapsedMillis());
        }
        Map<String, Object> map = metrics.getIfExists();
        if (map != null) {
            msg.append("metrics: ");
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                msg.append("\t%s=%s", entry.getKey(), jsonString(signature, entry.getValue()));
            }
            msg.appendLine();
        }
        return msg.toString();
    }

    private String jsonString(Signature signature, Object... args) {
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
        return quietly(() -> toJsonString(list.size() == 1 ? list.get(0) : list));
    }

    protected final Object defaultValue(Signature signature) {
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null) {
            return null;
        }
        return Reflects.defaultValue(methodSignature.getReturnType());
    }
}
