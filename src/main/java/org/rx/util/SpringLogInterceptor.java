package org.rx.util;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.rx.bean.Tuple;
import org.rx.core.StringBuilder;

import static org.rx.core.Contract.toJsonString;

public class SpringLogInterceptor {
    @SneakyThrows
    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) {
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
    //            return Reflects.defaultValue(method.getReturnType());
    protected Object onException(Signature signature, Exception e, StringBuilder msg) throws Throwable {
        msg.appendLine("Error:\t\t\t%s", e.getMessage());
        throw e;
    }

    protected Tuple<String, String> getProcessFormat() {
        return Tuple.of("Parameters:\t\t%s", "ReturnValue:\t%s");
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
        if (!log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        StringBuilder msg = new StringBuilder();
        boolean hasError = false;
        try {
            msg.appendLine("Call %s", signature.getName());
            return onProcess(joinPoint, msg);
        } catch (Exception e) {
            try {
                return onException(signature, e, msg);
            } catch (Throwable ie) {
                hasError = true;
                throw ie;
            }
        } finally {
            if (hasError) {
                log.error(msg.toString());
            } else {
                log.info(msg.toString());
            }
        }
    }
}
