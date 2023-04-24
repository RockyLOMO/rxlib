package org.rx.annotation;

import java.lang.annotation.*;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;

@Target({METHOD, FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ErrorCode.ErrorCodes.class)
public @interface ErrorCode {
    @Target({METHOD, FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface ErrorCodes {
        ErrorCode[] value();
    }

    enum MessageFormatter {
        StringFormat,
        MessageFormat
    }

    String value() default "";

    Class<? extends Throwable> cause() default Exception.class;

    MessageFormatter messageFormatter() default MessageFormatter.MessageFormat;
}
