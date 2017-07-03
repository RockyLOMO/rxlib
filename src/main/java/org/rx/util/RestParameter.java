package org.rx.util;

import java.lang.annotation.*;

/**
 * Created by za-wangxiaoming on 2017/7/3.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RestParameter {
    String name();
}
