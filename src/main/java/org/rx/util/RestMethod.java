package org.rx.util;

import java.lang.annotation.*;

/**
 * Created by za-wangxiaoming on 2017/7/3.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestMethod {
    String apiName() default "";

    String httpMethod() default "POST";

    boolean isFormParam() default false;
}
