package org.rx.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface EnableLogging {
}
