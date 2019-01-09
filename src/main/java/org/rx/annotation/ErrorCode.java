package org.rx.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ErrorCode.ErrorCodes.class)
public @interface ErrorCode {
    @Target({ ElementType.METHOD, ElementType.FIELD })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface ErrorCodes {
        ErrorCode[] value();
    }

    enum MessageFormatter {
        KeyValueFormat,
        StringFormat,
        MessageFormat
    }

    String value() default "";

    Class<? extends Throwable> cause() default Exception.class;

    MessageFormatter messageFormatter() default MessageFormatter.KeyValueFormat;

    String[] messageKeys() default {};
}
