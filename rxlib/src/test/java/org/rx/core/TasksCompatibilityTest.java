package org.rx.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class TasksCompatibilityTest {
    @Test
    void completableFutureAsyncPoolPatchDisabledByDefault() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setPatchCompletableFutureAsyncPool(false);
            Method method = Tasks.class.getDeclaredMethod("initCompletableFutureAsyncPool");
            method.setAccessible(true);
            Boolean patched = (Boolean) method.invoke(null);
            assertFalse(patched);
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        });
        future.join();
    }
}
