package org.rx.util;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.rx.Logger;
import org.rx.bean.Tuple;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.rx.Contract.toJsonString;

public class LogInterceptor {
    private static final ThreadLocal TC = ThreadLocal.withInitial(() -> FALSE);

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
        return Logger.getSlf4j(signature.getDeclaringType());
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
