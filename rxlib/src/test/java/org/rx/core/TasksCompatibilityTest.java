package org.rx.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TasksCompatibilityTest {
    @Test
    void completableFutureAsyncPoolUsesTasksExecutor() throws Exception {
        Method method = Tasks.class.getDeclaredMethod("initCompletableFutureAsyncPool");
        method.setAccessible(true);
        method.invoke(null);

        String threadName = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName())
                .get(5, TimeUnit.SECONDS);
        assertTrue(threadName.contains(ThreadPool.POOL_NAME_PREFIX), threadName);
    }
}
