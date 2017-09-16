package org.rx;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ErrorCodes {
    ErrorCode[] value();
}
