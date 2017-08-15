package org.rx.validator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/14
 */
@Target({ ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFlag {
    static final int ParameterValues = 1;
    static final int Method          = 1 << 1;
    static final int All             = ParameterValues | Method;

    int value() default All;
}
