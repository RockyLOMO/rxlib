package org.rx.bean;

import lombok.SneakyThrows;
import org.rx.core.App;

import java.io.Serializable;

import static org.rx.core.App.*;

public interface NEnum<T extends Enum<T> & NEnum<T>> extends Serializable {
    static <T extends Enum<T> & NEnum<T>> T valueOf(Class<T> type, int value) {
        require(type);

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

    @SuppressWarnings(NON_WARNING)
    default FlagsEnum<T> flags(T... nEnum) {
        require(nEnum);

        FlagsEnum<T> flagsEnum = flags();
        flagsEnum.add(nEnum);
        return flagsEnum;
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    default String description() {
        Class type = this.getClass();
        return App.description(type.getField(((T) this).name()));
    }
}
