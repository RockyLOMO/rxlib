package org.rx.annotation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.util.Validator;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = Validator.RegexValidator.class)
public @interface ValidRegex {
    @RequiredArgsConstructor
    @Getter
    enum Flag {
        Email("邮箱", Strings.RegularExp.Email),
        Url("链接", Strings.RegularExp.Url),
        CitizenId("身份证", Strings.RegularExp.CitizenId),
        Mobile("手机", Strings.RegularExp.Mobile),
        Tel("座机", Strings.RegularExp.Telephone);

        private final String name;
        private final String regexp;
    }

    Flag value();

    String message() default "需要匹配{value}格式";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
