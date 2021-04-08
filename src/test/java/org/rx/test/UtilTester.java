package org.rx.test;

import lombok.Data;
import org.junit.jupiter.api.Test;
import org.rx.test.bean.PersonBean;
import org.rx.util.Validator;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class UtilTester {
    @Data
    public static class TwoPerson {
//        @NotNull
        public String name;
        @Valid
        public PersonBean person = new PersonBean();
    }

    @Test
    public void validate() {
        TwoPerson tp = new TwoPerson();
        Validator.validateBean(tp);
    }
}
