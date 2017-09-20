package org.rx;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ErrorCode.ErrorCodes.class)
public @interface ErrorCode {
    @Target({ ElementType.METHOD })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface ErrorCodes {
        ErrorCode[] value();
    }

    String value() default "";

    Class cause() default Exception.class;

    String[] messageKeys() default {};
}
