package org.rx.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

@Target({TYPE, CONSTRUCTOR, METHOD, PARAMETER, FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Metadata {
    String value() default "";

    boolean ignore() default false;

    String topic() default "";

    Class<?> topicClass() default Object.class;
}
