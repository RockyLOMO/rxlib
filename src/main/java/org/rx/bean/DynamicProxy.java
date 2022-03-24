package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.exception.InvalidException;
import org.rx.util.function.TripleFunc;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

@RequiredArgsConstructor
public class DynamicProxy implements MethodInterceptor, InvocationHandler {
    public interface Proxifier {
        <T> T rawObject();
    }

    static final Method PRM;

    static {
        try {
            PRM = Proxifier.class.getMethod("rawObject");
        } catch (NoSuchMethodException e) {
            throw InvalidException.sneaky(e);
        }
    }

    final TripleFunc<Method, DynamicProxy, Object> fn;
    final Object rawObject;
    @Getter
    Object proxyObject;
    MethodProxy method;
    Method jdkProxy;
    @Getter
    public Object[] arguments;

    public <T> T argument(int i) {
        return (T) arguments[i];
    }

    public <T> T fastInvoke() throws Throwable {
        if (rawObject == null) {
            throw new InvalidException("raw object is null");
        }
        return fastInvoke(rawObject);
    }

    public <T> T fastInvoke(Object instance) throws Throwable {
        if (jdkProxy != null) {
            return (T) jdkProxy.invoke(instance, arguments);
        }
        return (T) method.invoke(instance, arguments);
    }

    public <T> T fastInvokeSuper() throws Throwable {
        if (jdkProxy != null) {
            return (T) jdkProxy.invoke(proxyObject, arguments);
        }
        return (T) method.invokeSuper(proxyObject, arguments);
    }

    @Override
    public Object intercept(Object proxyObject, Method method, Object[] arguments, MethodProxy methodProxy) throws Throwable {
        if (method == PRM) {
            return rawObject;
        }

        this.proxyObject = proxyObject;
        this.method = methodProxy;
        this.arguments = arguments;
        return fn.invoke(method, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method == PRM) {
            return rawObject;
        }

        proxyObject = proxy;
        arguments = args;
        jdkProxy = method;
        return fn.invoke(method, this);
    }
}
