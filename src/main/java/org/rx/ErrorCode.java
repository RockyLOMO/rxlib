package org.rx;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ErrorCodes.class)
public @interface ErrorCode {
    String value() default "";

    Class exception() default Exception.class;

    String[] messageKeys() default {};
}
