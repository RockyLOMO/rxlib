package org.rx.exception;

import lombok.Getter;
import org.rx.bean.DynamicProxyBean;

import java.lang.reflect.Method;

@Getter
public class FallbackException extends InvalidException {
    private static final long serialVersionUID = -1335264296697544072L;
    final Method method;
    final DynamicProxyBean proxyBean;
    final Object target;
    final Object fallbackTarget;
    final Throwable error;
    final Throwable fallbackError;

    public FallbackException(Method method, DynamicProxyBean proxyBean, Object target, Object fallbackTarget,
                             Throwable error, Throwable fallbackError) {
        super(fallbackError);
        fallbackError.addSuppressed(error);
        this.method = method;
        this.proxyBean = proxyBean;
        this.target = target;
        this.fallbackTarget = fallbackTarget;
        this.error = error;
        this.fallbackError = fallbackError;
    }
}
