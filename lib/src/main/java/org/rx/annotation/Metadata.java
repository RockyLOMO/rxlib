package org.rx.annotation;

import java.lang.annotation.*;

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
