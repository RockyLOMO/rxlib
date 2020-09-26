package org.rx.test.common;

import com.google.common.base.Stopwatch;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.Action;

import java.util.concurrent.TimeUnit;

@Slf4j
public class TestUtil {
    @SneakyThrows
    public static void invoke(String name, Action action) {
        int count = 10000;
        Stopwatch stopwatch = Stopwatch.createStarted();
        for (int i = 0; i < count; i++) {
            action.invoke();
        }
        log.info("Invoke {} elapsed {}ms", name, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }
}
