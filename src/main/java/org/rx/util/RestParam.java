package org.rx.util;

import java.lang.annotation.*;

/**
 * Created by wangxiaoming on 2017/7/3.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestParam {
    String value() default "";

    String name() default "";
}
