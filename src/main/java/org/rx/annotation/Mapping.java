package org.rx.annotation;

import org.rx.util.BeanMapConverter;
import org.rx.util.NullValueMappingStrategy;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Repeatable(Mapping.Mappings.class)
public @interface Mapping {
    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @interface Mappings {
        Mapping[] value();
    }

    String target();

    String source() default "";

    String format() default "";

    boolean ignore() default false;

    String defaultValue() default "";

    NullValueMappingStrategy nullValueStrategy() default NullValueMappingStrategy.SetToNull;

    boolean trim() default false;

    Class<? extends BeanMapConverter> converter() default BeanMapConverter.class;
}
