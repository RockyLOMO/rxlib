package org.rx.util;

import java.lang.annotation.*;

/**
 * Created by za-wangxiaoming on 2017/7/3.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestMethod {
    String value() default "";

    String path() default "";

    String method() default "POST";

    boolean isFormParam() default false;
}
