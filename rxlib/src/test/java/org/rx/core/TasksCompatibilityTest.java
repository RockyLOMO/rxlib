package org.rx.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class TasksCompatibilityTest {
    @Test
    void completableFutureAsyncPoolPatchDisabledByDefault() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        boolean old = conf.isPatchCompletableFutureAsyncPool();
        conf.setPatchCompletableFutureAsyncPool(false);
        Method method = Tasks.class.getDeclaredMethod("initCompletableFutureAsyncPool");
        method.setAccessible(true);
        try {
            Boolean patched = (Boolean) method.invoke(null);
            assertFalse(patched);
        } finally {
            conf.setPatchCompletableFutureAsyncPool(old);
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        });
        future.join();
    }
}
