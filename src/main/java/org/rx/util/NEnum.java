package org.rx.util;

import org.rx.common.App;
import org.rx.common.Contract;
import org.rx.common.NQuery;
import org.rx.common.SystemException;

import java.util.*;

import static org.rx.common.Contract.*;

public interface NEnum {
    static <T extends Enum<T> & NEnum> T valueOf(Class<T> type, int value) {
        require(type);

        for (T val : type.getEnumConstants()) {
            if (val.getValue() == value) {
                return val;
            }
        }
        return null;
    }

    static <T extends Enum<T> & NEnum> T fromStrings(Class<T> type, String strings) {
        require(type, strings);

        NEnum val = null;
        for (String n : App.split(strings, ", ")) {
            NEnum e = Enum.valueOf(type, n);
            if (val == null) {
                val = e;
                continue;
            }
            val.add(e);
        }
        return (T) val;
    }

    int getValue();

    default int getFlags() {
        return 0;
    }

    default void setFlags(int flags) {
    }

    @SuppressWarnings(Contract.AllWarnings)
    default void add(NEnum... vals) {
        require(vals);

        int flags = this.getFlags();
        for (NEnum val : vals) {
            flags |= val.getValue();
        }
        this.setFlags(flags);
    }

    @SuppressWarnings(Contract.AllWarnings)
    default void remove(NEnum... vals) {
        require(vals);

        int flags = this.getFlags();
        for (NEnum val : vals) {
            flags &= ~val.getValue();
        }
        this.setFlags(flags);
    }

    @SuppressWarnings(Contract.AllWarnings)
    default boolean has(NEnum... vals) {
        require(vals);

        int flags = 0;
        for (NEnum val : vals) {
            flags |= val.getValue();
        }
        return (this.getFlags() & flags) == flags;
    }

    default <T extends Enum<T> & NEnum> List<T> toEnums() {
        List<T> result = new ArrayList<>();
        for (NEnum val : this.getClass().getEnumConstants()) {
            if (this.has(val)) {
                result.add((T) val);
            }
        }
        return result;
    }

    default String toStrings() {
        return String.join(", ", NQuery.of(toEnums()).select(p -> p.toString()).toList());
    }

    default String toDescriptions() {
        Class type = this.getClass();
        return String.join(", ", NQuery.of(toEnums()).select(p -> {
            try {
                return toDescription(type.getField(((Enum) p).name()));
            } catch (Exception e) {
                throw SystemException.wrap(e);
            }
        }));
    }
}
