package org.rx.test;

import lombok.Data;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.exception.ExceptionLevel;
import org.rx.core.exception.InvalidException;
import org.rx.test.bean.PersonBean;
import org.rx.util.Validator;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;

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

    @Test
    @SneakyThrows
    public void validate() {
        NQuery.of(Arrays.toList(1, 2, 3, 4), true).takeWhile((p) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getId() + "=" + p);
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
}
