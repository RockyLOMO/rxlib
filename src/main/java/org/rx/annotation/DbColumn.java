package org.rx.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(FIELD)
@Retention(RUNTIME)
@Documented
public @interface DbColumn {
    String name() default "";

    int length() default 0;

    boolean primaryKey() default false;

    boolean autoIncrement() default false;

    boolean large() default false;
}
