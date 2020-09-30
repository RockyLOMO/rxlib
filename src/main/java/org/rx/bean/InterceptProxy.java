package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.sf.cglib.proxy.MethodProxy;

@Getter
@RequiredArgsConstructor
public class InterceptProxy {
    private final Object proxyObject;
    private final MethodProxy method;
    public final Object[] arguments;

    public <T> T argument(int i) {
        return (T) arguments[i];
    }

    public <T> T fastInvoke(Object instance) throws Throwable {
        return (T) method.invoke(instance, arguments);
    }
}
