package org.rx.bean;

import lombok.Getter;
import org.rx.util.function.TripleFunc;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class DynamicProxyBean implements MethodInterceptor, InvocationHandler {
    final TripleFunc<Method, DynamicProxyBean, Object> fn;
    @Getter
    Object proxyObject;
    MethodProxy method;
    Method jdkProxy;
    @Getter
    public Object[] arguments;

    public DynamicProxyBean(TripleFunc<Method, DynamicProxyBean, Object> fn) {
        this.fn = fn;
    }

    public <T> T argument(int i) {
        return (T) arguments[i];
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
        DynamicProxyBean snapshot = new DynamicProxyBean(fn);
        snapshot.proxyObject = proxyObject;
        snapshot.method = methodProxy;
        snapshot.arguments = arguments;
        return fn.invoke(method, snapshot);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        DynamicProxyBean snapshot = new DynamicProxyBean(fn);
        snapshot.proxyObject = proxy;
        snapshot.arguments = args;
        snapshot.jdkProxy = method;
        return fn.invoke(method, snapshot);
    }
}
