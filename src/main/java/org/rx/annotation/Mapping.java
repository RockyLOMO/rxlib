package org.rx.annotation;

import net.sf.cglib.core.Converter;
import org.rx.util.NullValueMappingStrategy;

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

    String format() default "";

    boolean ignore() default false;

    String defaultValue() default "";

    NullValueMappingStrategy nullValueMappingStrategy() default NullValueMappingStrategy.SetToNull;

    boolean trim() default false;

    Class<? extends Converter> converter() default Converter.class;
}
