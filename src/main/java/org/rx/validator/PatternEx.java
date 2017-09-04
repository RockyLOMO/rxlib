package org.rx.validator;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER })
@Retention(RUNTIME)
@Constraint(validatedBy = PatternExValidator.class)
@Documented
public @interface PatternEx {
    static enum Flag {
        Email("邮箱", "^[\\w-]+(\\.[\\w-]+)*@[\\w-]+(\\.[\\w-]+)+$"),
        Url("链接",
                "^((http|https|ftp):\\/\\/)?(\\w(\\:\\w)?@)?([0-9a-z_-]+\\.)*?([a-z0-9-]+\\.[a-z]{2,6}(\\.[a-z]{2})?(\\:[0-9]{2,6})?)((\\/[^?#<>\\/\\\\*\":]*)+(\\?[^#]*)?(#.*)?)?$"),
        CitizenId("身份证", "^(\\d{15}$|^\\d{18}$|^\\d{17}(\\d|X|x))$"),
        Mobile("手机", "^0{0,1}1[3|5|7|8]\\d{9}$"),
        Tel("座机", "(\\d+-)?(\\d{4}-?\\d{7}|\\d{3}-?\\d{8}|^\\d{7,8})(-\\d+)?");

        private String name;
        private String regexp;

        public String getRegexp() {
            return regexp;
        }

        Flag(String name, String regexp) {
            this.name = name;
            this.regexp = regexp;
        }

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
