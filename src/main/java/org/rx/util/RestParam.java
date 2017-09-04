package org.rx.util;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestParam {
    String value() default "";

    String name() default "";
}
