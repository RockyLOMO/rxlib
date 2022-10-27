package org.rx.test;

import com.google.common.base.Stopwatch;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.ResetEventWait;
import org.rx.core.Tasks;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.util.function.BiAction;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AbstractTester {
    static final String BASE_DIR = "./target/";
    static final int LOOP_COUNT = 10000;

    public static String path(String... paths) {
        Files.createDirectory(BASE_DIR);
        return Files.concatPath(BASE_DIR, paths);
    }

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
            double elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            log.info("Invoke {} times={} elapsed={}ms avg={}ms", name, count, elapsed, elapsed / count);
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

    final String host_devops = "devops.f-li.cn";
    final String host_cloud = "cloud.f-li.cn";
    final InetSocketAddress endpoint_3307 = Sockets.parseEndpoint("127.0.0.1:3307");
    final InetSocketAddress endpoint_3308 = Sockets.parseEndpoint("127.0.0.1:3308");
    final long oneSecond = 1000;
    final String str_name_wyf = "王湵范 wyf520";
    final String str_content = "youfan1024码农";
    final ResetEventWait wait = new ResetEventWait();

    @SneakyThrows
    protected synchronized void _wait() {
        wait();
    }

    protected synchronized void _notify() {
        notify();
    }
}
