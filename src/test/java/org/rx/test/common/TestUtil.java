package org.rx.test.common;

import com.google.common.base.Stopwatch;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TestUtil {
    private static final int LOOP_COUNT = 10000;

    public static void invoke(String name, BiAction<Integer> action) {
        invoke(name, action, LOOP_COUNT);
    }

    @SneakyThrows
    public static void invoke(String name, BiAction<Integer> action, int count) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            for (int i = 0; i < count; i++) {
                action.invoke(i);
            }
        } finally {
            log.info("Invoke {} times={} elapsed={}ms", name, count, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    public static void invokeAsync(String name, BiAction<Integer> action) {
        invoke(name, action, LOOP_COUNT);
    }

    @SneakyThrows
    public static void invokeAsync(String name, BiAction<Integer> action, int count) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            CountDownLatch latch = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                int finalI = i;
                Tasks.run(() -> {
                    try {
                        action.invoke(finalI);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            log.info("Invoke {} times={} elapsed={}ms", name, count, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }
}
