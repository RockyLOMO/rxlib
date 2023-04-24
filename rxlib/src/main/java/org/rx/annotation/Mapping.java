package org.rx.annotation;

import org.rx.util.BeanMapConverter;
import org.rx.util.BeanMapNullValueStrategy;

import java.lang.annotation.*;

import static java.lang.annotation.ElementType.METHOD;

@Target({METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Repeatable(Mapping.Mappings.class)
public @interface Mapping {
    @Target({METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @Inherited
    @interface Mappings {
        Mapping[] value();
    }

    String target();

    String source() default "";

    String format() default "";

    boolean ignore() default false;

    String defaultValue() default "";

    BeanMapNullValueStrategy nullValueStrategy() default BeanMapNullValueStrategy.SetToNull;

    boolean trim() default false;

    Class<? extends BeanMapConverter> converter() default BeanMapConverter.class;
}
