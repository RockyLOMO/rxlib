package org.rx.beans;

import lombok.SneakyThrows;
import org.rx.core.Contract;

import java.io.Serializable;

import static org.rx.core.Contract.*;

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

    @SuppressWarnings(NonWarning)
    default FlagsEnum<T> add(T... nEnum) {
        require(nEnum);

        FlagsEnum<T> flagsEnum = flags();
        flagsEnum.add(nEnum);
        return flagsEnum;
    }

    @SuppressWarnings(NonWarning)
    @SneakyThrows
    default String toDescription() {
        Class type = this.getClass();
        return Contract.toDescription(type.getField(((T) this).name()));
    }
}
