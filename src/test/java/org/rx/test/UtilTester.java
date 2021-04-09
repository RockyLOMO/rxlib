package org.rx.test;

import lombok.Data;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.Arrays;
import org.rx.test.bean.PersonBean;
import org.rx.util.Validator;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
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
        TwoPerson tp = new TwoPerson();
//        Validator.validateBean(tp);

        Validator.validateMethod(TwoPerson.class.getMethod("renew", List.class), tp, new Object[]{Arrays.toList(tp.person)}, () -> "a");
//        List<TwoPerson> list = Collections.singletonList(tp);
//        Validator.validateBean(list);
    }
}
