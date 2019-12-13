package org.rx.util;

public interface BeanMapConverter<TS, TT> {
    TT convert(TS sourceValue, Class<TT> targetType, String propertyName);
}
