package org.rx.bean;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.Extends;

import java.io.Serializable;

import static org.rx.core.Constants.NON_UNCHECKED;

public interface NEnum<T extends Enum<T> & NEnum<T>> extends Serializable {
    static <T extends Enum<T> & NEnum<T>> T valueOf(@NonNull Class<T> type, int value) {
        for (T nEnum : type.getEnumConstants()) {
            if (nEnum.getValue() == value) {
                return nEnum;
            }
        }
        return null;
    }

    int getValue();

    default FlagsEnum<T> flags() {
        return new FlagsEnum<>(this);
    }

    @SuppressWarnings(NON_UNCHECKED)
    default FlagsEnum<T> flags(T... nEnum) {
        FlagsEnum<T> flagsEnum = flags();
        flagsEnum.add(nEnum);
        return flagsEnum;
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    default String description() {
        Class<?> type = this.getClass();
        return Extends.description(type.getField(((T) this).name()));
    }
}
