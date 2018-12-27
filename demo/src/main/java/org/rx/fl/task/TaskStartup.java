package org.rx.fl.task;

import lombok.extern.slf4j.Slf4j;
import org.rx.fl.service.TbMedia;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Order(value = 1)
@Slf4j
public class TaskStartup implements CommandLineRunner {
    public static final ScheduledExecutorService Scheduler = Executors.newScheduledThreadPool(8);
//    @Resource
//    private TbMedia tbMedia;

    @Override
    public void run(String... strings) throws Exception {
        long period = 10 * 1000;
//        scheduler.scheduleWithFixedDelay(() -> tbMedia.checkLogin(), 200, period, TimeUnit.MILLISECONDS);
    }
}
