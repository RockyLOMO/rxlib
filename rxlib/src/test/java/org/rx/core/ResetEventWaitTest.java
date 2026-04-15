package org.rx.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResetEventWaitTest {

    @Test
    void waitOne_timeoutIgnoresPrematureNotify() throws Exception {
        ResetEventWait wait = new ResetEventWait(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread notifier = new Thread(() -> {
            try {
                Thread.sleep(60);
                synchronized (wait) {
                    wait.notifyAll();
                }
                Thread.sleep(80);
                wait.set();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        notifier.start();

        assertTrue(wait.waitOne(1000), "提前 notify 不应被误判为超时");
        assertTrue(done.await(2, TimeUnit.SECONDS));
    }

    @Test
    void waitOne_timeoutReturnsFalseWhenNeverSignaled() {
        ResetEventWait wait = new ResetEventWait(false);
        long start = System.nanoTime();
        assertFalse(wait.waitOne(120));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= 100, "超时等待不应过早返回: " + elapsedMillis + "ms");
    }
}
