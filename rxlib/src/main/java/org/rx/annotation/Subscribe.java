package org.rx.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

@Target({METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Subscribe {
    @AliasFor("topic")
    String value() default "";

    @AliasFor("value")
    String topic() default "";

    Class<?> topicClass() default Object.class;
}
