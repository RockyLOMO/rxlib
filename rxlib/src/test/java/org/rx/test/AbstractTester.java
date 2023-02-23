package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.ResetEventWait;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.util.function.BiAction;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class AbstractTester {
    public static final String BASE_DIR = "./target/";
    public static final int LOOP_COUNT = 10000;

    public static String path(String... paths) {
        Files.createDirectory(BASE_DIR);
        return Files.concatPath(BASE_DIR, paths);
    }

    public static void invoke(String name, BiAction<Integer> action) {
        invoke(name, action, LOOP_COUNT);
    }

    @SneakyThrows
    public static void invoke(String name, BiAction<Integer> action, int count) {
        long start = System.nanoTime();
        try {
            for (int i = 0; i < count; i++) {
                action.invoke(i);
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            log.info("Invoke {} times={} elapsed={} avg={}", name, count, Sys.formatNanosElapsed(elapsed), Sys.formatNanosElapsed(elapsed / count));
        }
    }

    public static void invokeAsync(String name, BiAction<Integer> action) {
        invoke(name, action, LOOP_COUNT);
    }

    @SneakyThrows
    public static void invokeAsync(String name, BiAction<Integer> action, int count) {
        long start = System.nanoTime();
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
            long elapsed = System.nanoTime() - start;
            log.info("Invoke {} times={} elapsed={}", name, count, Sys.formatNanosElapsed(elapsed));
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
