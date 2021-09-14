package org.rx.test;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBufUtil;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.Tasks;
import org.rx.core.exception.ExceptionLevel;
import org.rx.core.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.security.MD5Util;
import org.rx.test.bean.PersonBean;
import org.rx.util.Validator;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.lang.ref.PhantomReference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

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

        System.out.println(MessageFormatter.arrayFormat("hello {}!", new Object[]{1024}, new InvalidException("error")).getMessage());

        System.out.println("log:" + App.log("hello {}!", 1));
        System.out.println("log:" + App.log("hello {}!", 1, new InvalidException("a").level(ExceptionLevel.USER_OPERATION)));
    }

    WeakReference<Integer> x = new WeakReference<>(10);

    @Test
    public void security() {
//        SoftReference<Integer> x = new SoftReference<>(10);
        System.out.println(x.get());

        for (int i = 0; i < 10; i++) {
            System.gc();
            System.out.println("x:" + x.get());
        }
    }
}
