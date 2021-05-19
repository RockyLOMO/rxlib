package org.rx.test.common;

import com.google.common.base.Stopwatch;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.Action;

import java.util.concurrent.TimeUnit;

@Slf4j
public class TestUtil {
    private static final int LOOP_COUNT = 10000;

    public static void invoke(String name, Action action) {
        invoke(name, action, LOOP_COUNT);
    }

    @SneakyThrows
    public static void invoke(String name, Action action, int count) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        for (int i = 0; i < count; i++) {
            action.invoke();
        }
        log.info("Invoke {} times={} elapsed={}ms", name, count, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }
}
