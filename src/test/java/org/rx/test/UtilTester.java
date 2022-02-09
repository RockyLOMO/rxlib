package org.rx.test;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.Tasks;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.test.bean.PersonBean;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
public class UtilTester {
    @Data
    public static class TwoPerson {
        //        @NotNull
        public String name;
        @Valid
        public PersonBean person = new PersonBean();

        @Validated
        @NotNull
        public String renew(@NotNull @Valid List<PersonBean> person) {
            return null;
        }
    }

    List<Integer> queue = new ArrayList<>();

    @SneakyThrows
    @Test
    public void productAndConsume() {
        final boolean[] run = {true};
        Object lock = new Object();
        int bufSize = 5, max = bufSize * 10;
        AtomicInteger c = new AtomicInteger();
        Tasks.run(() -> {
            while (run[0]) {
                synchronized (lock) {
                    if (queue.size() < bufSize) {
                        int v = c.incrementAndGet();
                        queue.add(v);
                        log.info("product {}", v);
                        if (v == max) {
                            run[0] = false;
                        }
                        continue;
                    }
                    lock.notifyAll();
                    lock.wait();
                }
            }
        });
        Tasks.run(() -> {
            while (run[0]) {
                synchronized (lock) {
                    if (queue.size() < bufSize) {
                        lock.wait();
                        continue;
                    }
                    for (Integer v : queue) {
                        log.info("consume {}", v);
                    }
                    queue.clear();
                    lock.notifyAll();
                }
            }
        });
        System.in.read();
    }

    @Test
    @SneakyThrows
    public void validate() {
        NQuery.of(Arrays.toList(1, 2, 3, 4), true).takeWhile((p) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + "=" + p);
            return true;
        });

//        TwoPerson tp = new TwoPerson();
////        Validator.validateBean(tp);
//
//        Validator.validateMethod(TwoPerson.class.getMethod("renew", List.class), tp, new Object[]{null}, () -> "a");
////        List<TwoPerson> list = Collections.singletonList(tp);
////        Validator.validateBean(list);

        InvalidException error = new InvalidException("error");
        System.out.println(MessageFormatter.arrayFormat("hello {}!", new Object[]{1024}, error));
        System.out.println("--st--\n" + ExceptionUtils.getStackTrace(error));
    }

    @Test
    public void netIp() {
        String s = Sockets.DEFAULT_NAT_IPS.get(3);
        System.out.println(Pattern.matches(s, "192.168.31.7"));
    }
}
