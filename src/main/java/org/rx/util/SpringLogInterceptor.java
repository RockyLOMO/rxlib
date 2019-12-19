package org.rx.util;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.rx.beans.Tuple;
import org.rx.core.StringBuilder;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.rx.core.Contract.toJsonString;

public class SpringLogInterceptor {
//    private static final ThreadLocal<Boolean> threadStatic = ThreadLocal.withInitial(() -> FALSE);

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

    protected Object onException(Exception ex, StringBuilder msg) throws Throwable {
        msg.appendLine("Error:\t\t\t%s", ex.getMessage());
        throw ex;
    }

    protected Tuple<String, String> getProcessFormat() {
        return Tuple.of("Parameters:\t\t%s", "ReturnValue:\t%s");
    }

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
        if (
//                threadStatic.get() ||
                        !log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        StringBuilder msg = new StringBuilder();
        try {
//            threadStatic.set(TRUE);
            msg.appendLine("Call %s", signature.getName());
            return onProcess(joinPoint, msg);
        } catch (Exception e) {
            return onException(e, msg);
        } finally {
            log.info(msg.toString());
//            threadStatic.set(FALSE);
        }
    }
}
