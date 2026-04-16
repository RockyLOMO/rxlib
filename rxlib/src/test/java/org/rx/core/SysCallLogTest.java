package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.LogStrategy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SysCallLogTest {
    static class CountingBuilder implements Sys.CallLogBuilder {
        final AtomicInteger snapshotCalls = new AtomicInteger();
        final AtomicInteger logCalls = new AtomicInteger();

        @Override
        public String buildParamSnapshot(Class<?> declaringType, String methodName, Object[] parameters) {
            snapshotCalls.incrementAndGet();
            return "{}";
        }

        @Override
        public String buildLog(Class<?> declaringType, String methodName, Object[] parameters, String paramSnapshot, Object returnValue, Throwable error, long elapsedNanos) {
            logCalls.incrementAndGet();
            return methodName;
        }
    }

    @Test
    void callLog_strategyNone_skipsDisplayNameAndParamSnapshot() {
        CountingBuilder builder = new CountingBuilder();
        AtomicInteger displayNameCalls = new AtomicInteger();

        String value = Sys.callLog(getClass(), "demoMethod", () -> {
            displayNameCalls.incrementAndGet();
            return "display-demoMethod";
        }, new Object[]{"arg"}, (Sys.ProceedFunc<String>) () -> "ok", builder, LogStrategy.NONE);

        assertEquals("ok", value);
        assertEquals(0, displayNameCalls.get(), "未写日志时不应构造 displayName");
        assertEquals(0, builder.snapshotCalls.get(), "未写日志时不应构造参数快照");
        assertEquals(0, builder.logCalls.get(), "未写日志时不应构造日志正文");
    }

    @Test
    void callLog_strategyAlways_buildsSnapshotBeforeProceed() {
        AtomicBoolean proceeded = new AtomicBoolean();
        AtomicInteger snapshotCalls = new AtomicInteger();
        Sys.CallLogBuilder builder = new Sys.CallLogBuilder() {
            @Override
            public String buildParamSnapshot(Class<?> declaringType, String methodName, Object[] parameters) {
                assertFalse(proceeded.get(), "参数快照应在 proceed.invoke 之前构建");
                snapshotCalls.incrementAndGet();
                return "{}";
            }

            @Override
            public String buildLog(Class<?> declaringType, String methodName, Object[] parameters, String paramSnapshot, Object returnValue, Throwable error, long elapsedNanos) {
                return methodName;
            }
        };

        String value = Sys.callLog(getClass(), "alwaysMethod", new Object[]{"a"}, (Sys.ProceedFunc<String>) () -> {
            proceeded.set(true);
            return "ok";
        }, builder, LogStrategy.ALWAYS);

        assertEquals("ok", value);
        assertEquals(1, snapshotCalls.get());
    }

    @Test
    void callLog_writeOnError_success_skipsSnapshot() {
        CountingBuilder builder = new CountingBuilder();
        String value = Sys.callLog(getClass(), "onErrorMethod", new Object[]{}, (Sys.ProceedFunc<String>) () -> "ok", builder, LogStrategy.WRITE_ON_ERROR);
        assertEquals("ok", value);
        assertEquals(0, builder.snapshotCalls.get());
        assertEquals(0, builder.logCalls.get());
    }

    @Test
    void callLog_writeOnNull_cleanArgs_nonNullReturn_skipsSnapshot() {
        CountingBuilder builder = new CountingBuilder();
        String value = Sys.callLog(getClass(), "onNullMethod", new Object[]{"x"}, (Sys.ProceedFunc<String>) () -> "ok", builder, LogStrategy.WRITE_ON_NULL);
        assertEquals("ok", value);
        assertEquals(0, builder.snapshotCalls.get());
        assertEquals(0, builder.logCalls.get());
    }
}
