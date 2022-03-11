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
    enum IndexKind {
        NONE,
        INDEX_ASC,
        INDEX_DESC,
        UNIQUE_INDEX_ASC,
        UNIQUE_INDEX_DESC
    }

    String name() default "";

    int length() default 0;

    boolean primaryKey() default false;

    boolean autoIncrement() default false;

    IndexKind index() default IndexKind.NONE;
}
