package org.rx.test.common;

import org.rx.core.ThreadPool;
import org.rx.net.http.HttpClient;

import java.math.BigDecimal;
import java.util.concurrent.BlockingQueue;

import static org.rx.core.App.MAX_INT;

public class SimpleThreadSizeCalculator extends ThreadSizeCalculator {
    @Override
    protected Runnable creatTask() {
        return () -> {
            HttpClient client = new HttpClient();
            client.get("http://www.baidu.com");
        };
    }

    @Override
    protected BlockingQueue createWorkQueue() {
        return new ThreadPool.ThreadQueue(MAX_INT);
    }

    public static void main(String[] args) {
        SimpleThreadSizeCalculator calculator = new SimpleThreadSizeCalculator();
        calculator.calculateBoundaries(new BigDecimal(1.0), new BigDecimal(1024 * 1024));
    }
}
