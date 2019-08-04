package org.rx.util;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.rx.beans.Tuple;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.rx.common.Contract.require;
import static org.rx.common.Contract.toJsonString;

@Slf4j
public class LogInterceptor {
    private static final ThreadLocal TC = ThreadLocal.withInitial(() -> FALSE);

    public static org.slf4j.Logger getSlf4j(Class signature) {
        return getSlf4j(signature, Collections.emptyList(), null);
    }

    public static org.slf4j.Logger getSlf4j(Class signature, List<String> regs, String cacheMethodName) {
        require(signature, regs);

        Function<String, Logger> func = k -> {
            Class owner = signature;
            if (!regs.isEmpty()) {
                String fType;
                if ((fType = NQuery.of(Thread.currentThread().getStackTrace()).select(p -> p.getClassName())
                        .firstOrDefault(p -> NQuery.of(regs).any(reg -> Pattern.matches(reg, p)))) != null) {
                    owner = App.loadClass(fType, false);
                }
            }
            return org.slf4j.LoggerFactory.getLogger(owner);
        };
        return cacheMethodName != null ? App.getOrStore("Logger" + signature.getName() + cacheMethodName, func)
                : func.apply(null);
    }

    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) throws Throwable {
        Object p = joinPoint.getArgs();
        switch (joinPoint.getArgs().length) {
            case 0:
                p = "NULL";
                break;
            case 1:
                p = joinPoint.getArgs()[0];
                break;
        }
        Tuple<String, String> tuple = getProcessFormat();
        msg.appendLine(tuple.left, toJsonString(p));
        Object r = joinPoint.proceed();
        msg.appendLine(tuple.right, toJsonString(r));
        return r;
    }

    protected Object onException(Exception ex, StringBuilder msg) throws Throwable {
        msg.appendLine("Error:\t\t\t%s", ex.getMessage());
        throw ex;
    }

    protected Tuple<String, String> getProcessFormat() {
        return Tuple.of("Parameters:\t\t%s", "ReturnValue:\t%s");
    }

    protected org.slf4j.Logger getLogger(Signature signature) {
        return getSlf4j(signature.getDeclaringType());
    }

    public final Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        org.slf4j.Logger log;
        if ((Boolean) TC.get() || (log = getLogger(signature)) == null || !log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        StringBuilder msg = new StringBuilder();
        try {
            TC.set(TRUE);
            msg.appendLine("Call %s", signature.getName());
            return onProcess(joinPoint, msg);
        } catch (Exception e) {
            return onException(e, msg);
        } finally {
            log.info(msg.toString());
            TC.set(FALSE);
        }
    }
}
