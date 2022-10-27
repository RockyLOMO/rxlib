package org.rx.spring;

import java.lang.reflect.Method;

public interface IRequireSignIn {
    boolean isSignIn(Method method, Object[] args);
}
