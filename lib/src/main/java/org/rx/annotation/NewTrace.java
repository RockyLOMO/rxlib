package org.rx.annotation;

import java.lang.annotation.*;

import static java.lang.annotation.ElementType.*;

@Target({TYPE, CONSTRUCTOR, METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NewTrace {
}
