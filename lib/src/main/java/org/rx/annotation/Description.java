package org.rx.annotation;

import java.lang.annotation.*;

import static java.lang.annotation.ElementType.*;

@Target({TYPE, CONSTRUCTOR, METHOD, PARAMETER, FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Description {
    String value() default "";
}
