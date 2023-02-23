package org.rx;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.util.function.BiAction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    public static void invoke(String name, BiAction<Integer> action, int loopCount) {
        long start = System.nanoTime();
        try {
            for (int i = 0; i < loopCount; i++) {
                action.invoke(i);
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            log.info("Invoke {} times={} elapsed={} avg={}", name, loopCount, Sys.formatNanosElapsed(elapsed), Sys.formatNanosElapsed(elapsed / loopCount));
        }
    }

    public static void invokeAsync(String name, BiAction<Integer> action) {
        invokeAsync(name, action, LOOP_COUNT, Constants.CPU_THREADS * 2);
    }

    @SneakyThrows
    public static void invokeAsync(String name, BiAction<Integer> action, int loopCount, int threadSize) {
        ThreadPool pool = new ThreadPool(threadSize, 256, "dev");
        long start = System.nanoTime();
        try {
            CountDownLatch latch = new CountDownLatch(loopCount);
            for (int i = 0; i < loopCount; i++) {
                int finalI = i;
                pool.run(() -> {
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
            log.info("Invoke {} times={} elapsed={}", name, loopCount, Sys.formatNanosElapsed(elapsed));
        }
    }

    public final String host_devops = "devops.f-li.cn";
    public final String host_cloud = "cloud.f-li.cn";
    public final InetSocketAddress endpoint_3307 = Sockets.parseEndpoint("127.0.0.1:3307");
    public final InetSocketAddress endpoint_3308 = Sockets.parseEndpoint("127.0.0.1:3308");
    public final long oneSecond = 1000;
    public final String str_name_wyf = "王湵范 wyf520";
    public final String str_content = "youfan1024码农";
    public final byte[] bytes_content = str_content.getBytes(StandardCharsets.UTF_8);
    public final ResetEventWait wait = new ResetEventWait();

    @SneakyThrows
    protected synchronized void _wait() {
        wait();
    }

    protected synchronized void _notify() {
        notify();
    }
}
