package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.Decimal;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReflectsCompatibilityTest {
    interface DefaultGreeting {
        default String hello() {
            return "hello";
        }
    }

    interface ConcurrentProxy {
        String echo(String value);

        String triple(String a, String b, String c);
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
    void dynamicProxyBeanKeepsInvocationStatePerConcurrentCall() throws Exception {
        CountDownLatch echoEntered = new CountDownLatch(1);
        CountDownLatch tripleEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ConcurrentProxy proxy = Sys.proxy(ConcurrentProxy.class, (m, p) -> {
            Object[] snapshot = p.arguments;
            if ("echo".equals(m.getName())) {
                echoEntered.countDown();
                assertTrue(tripleEntered.await(3, TimeUnit.SECONDS));
                assertSame(snapshot, p.arguments);
                release.countDown();
                return p.arguments[0];
            }
            if ("triple".equals(m.getName())) {
                assertTrue(echoEntered.await(3, TimeUnit.SECONDS));
                tripleEntered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                assertSame(snapshot, p.arguments);
                return p.arguments[0] + p.arguments[1].toString() + p.arguments[2];
            }
            return null;
        });

        Thread t1 = new Thread(() -> {
            try {
                assertEquals("a", proxy.echo("a"));
            } catch (Throwable e) {
                error.compareAndSet(null, e);
                release.countDown();
            }
        }, "dynamic-proxy-echo-test");
        Thread t2 = new Thread(() -> {
            try {
                assertEquals("bcd", proxy.triple("b", "c", "d"));
            } catch (Throwable e) {
                error.compareAndSet(null, e);
                release.countDown();
            }
        }, "dynamic-proxy-triple-test");
        t1.start();
        t2.start();
        t1.join(4000);
        t2.join(4000);

        assertNull(error.get());
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
