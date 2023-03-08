package org.rx;

import org.rx.net.http.HttpClient;

import java.math.BigDecimal;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

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
        return new LinkedTransferQueue<>();
    }

    public static void main(String[] args) {
        SimpleThreadSizeCalculator calculator = new SimpleThreadSizeCalculator();
        calculator.calculateBoundaries(new BigDecimal(1.0), new BigDecimal(1024 * 1024));
    }
}
