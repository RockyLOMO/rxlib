package org.rx.util;

import org.rx.App;
import org.rx.Contract;

import java.util.*;
import java.util.stream.Collectors;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

public interface NEnum {
    static <T extends Enum<T> & NEnum> T valueOf(Class<T> type, int value) {
        require(type);

        for (T enumConstant : type.getEnumConstants()) {
            if (enumConstant.getValue() == value) {
                return enumConstant;
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

        int v = 0;
        for (NEnum val : vals) {
            v |= val.getValue();
        }
        return (this.getFlags() & v) == v;
    }

    default <T extends Enum<T> & NEnum> List<T> toEnums() {
        List<T> result = new ArrayList<>();
        for (Object o : this.getClass().getEnumConstants()) {
            NEnum e = as(o, NEnum.class);
            if (this.has(e)) {
                result.add((T) e);
            }
        }
        return result;
    }

    default String toStrings() {
        return String.join(", ", toEnums().stream().map(p -> p.toString()).collect(Collectors.toList()));
    }
}
