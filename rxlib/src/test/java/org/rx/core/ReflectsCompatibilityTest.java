package org.rx.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReflectsCompatibilityTest {
    interface DefaultGreeting {
        default String hello() {
            return "hello";
        }
    }

    @Test
    void invokeDefaultMethodSupportsProxyInstance() throws Exception {
        DefaultGreeting proxy = (DefaultGreeting) Proxy.newProxyInstance(
                DefaultGreeting.class.getClassLoader(),
                new Class<?>[]{DefaultGreeting.class},
                (p, method, args) -> null);
        String value = Reflects.invokeDefaultMethod(DefaultGreeting.class.getMethod("hello"), proxy);
        assertEquals("hello", value);
    }
}
