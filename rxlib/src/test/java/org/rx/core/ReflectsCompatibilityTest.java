package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.Decimal;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReflectsCompatibilityTest {
    interface DefaultGreeting {
        default String hello() {
            return "hello";
        }
    }

    public static class InvokeTarget {
        public static final Holder HOLDER = new Holder("field");

        public static String echo(String value) {
            return "s:" + value;
        }

        public static String echo(int value) {
            return "i:" + value;
        }

        public String instanceEcho(String value) {
            return "bean:" + value;
        }
    }

    public static class Holder {
        final String value;

        Holder(String value) {
            this.value = value;
        }

        public String text() {
            return value;
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

    @Test
    void invokeExpressionSupportsStaticOverloadAndFieldChain() {
        assertEquals("s:abc", Reflects.invokeExpression(
                "org.rx.core.ReflectsCompatibilityTest$InvokeTarget.echo", Arrays.asList("abc")));
        assertEquals("i:7", Reflects.invokeExpression(
                "org.rx.core.ReflectsCompatibilityTest$InvokeTarget.echo", Arrays.asList(Integer.valueOf(7))));
        assertEquals("field", Reflects.invokeExpression(
                "org.rx.core.ReflectsCompatibilityTest$InvokeTarget.HOLDER.text", null));
    }

    @Test
    void invokeExpressionSupportsInstanceResolver() {
        Object value = Reflects.invokeExpression(
                "org.rx.core.ReflectsCompatibilityTest$InvokeTarget.instanceEcho",
                Arrays.asList("x"), p -> new InvokeTarget());
        assertEquals("bean:x", value);
    }

    @Test
    void changeTypeUsesRegisteredConverterBeforeStringValueOfFallback() {
        assertEquals(new BigDecimal("1.25"), Reflects.changeType("1.25", BigDecimal.class));
        assertEquals(new BigDecimal("2.00"), Reflects.changeType(Decimal.valueOf(2D), BigDecimal.class));
    }
}
