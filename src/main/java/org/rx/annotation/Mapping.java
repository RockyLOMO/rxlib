package org.rx.annotation;

import java.lang.annotation.*;

@Repeatable(Mapping.Mappings.class)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface Mapping {
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.METHOD})
    @interface Mappings {
        Mapping[] value();
    }

    String target();

    String source() default "";

    String dateFormat() default "";

    String numberFormat() default "";

    String constant() default "";

    boolean ignore() default false;

    String defaultValue() default "";

    NullValueCheckStrategy nullValueCheckStrategy() default NullValueCheckStrategy.ON_IMPLICIT_CONVERSION;

    NullValuePropertyMappingStrategy nullValuePropertyMappingStrategy() default NullValuePropertyMappingStrategy.SET_TO_NULL;
}
