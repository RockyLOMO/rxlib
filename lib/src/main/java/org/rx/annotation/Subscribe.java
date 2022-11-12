package org.rx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

@Target({METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Subscribe {
    String value() default "";
}
