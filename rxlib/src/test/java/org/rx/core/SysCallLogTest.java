package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.LogStrategy;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
