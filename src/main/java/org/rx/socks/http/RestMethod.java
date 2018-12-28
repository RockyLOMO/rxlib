package org.rx.socks.http;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestMethod {
    String value() default "";

    String path() default "";

    String method() default "POST";

    boolean isFormParam() default false;
}
