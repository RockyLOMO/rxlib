package org.rx.util.validator;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface EnableValid {
    int ParameterValues = 1;
    int Method = 1 << 1;
    int All = ParameterValues | Method;

    int value() default All;
}
