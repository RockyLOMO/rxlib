package org.rx.util.validator;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = PatternExValidator.class)
public @interface PatternEx {
    @RequiredArgsConstructor
    enum Flag {
        Email("邮箱", Strings.RegularExp.Email),
        Url("链接", Strings.RegularExp.Url),
        CitizenId("身份证", Strings.RegularExp.CitizenId),
        Mobile("手机", Strings.RegularExp.Mobile),
        Tel("座机", Strings.RegularExp.Telephone);

        private final String name;
        @Getter
        private final String regexp;

        @Override
        public String toString() {
            return name;
        }
    }

    Flag value();

    String message() default "需要匹配{value}格式";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
