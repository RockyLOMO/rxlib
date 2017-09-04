package org.rx.common;

import java.util.*;
import java.util.stream.Collectors;

import static org.rx.common.Contract.require;
import static org.rx.util.App.As;
import static org.rx.util.App.split;

public interface NEnum {
    int getValue();

    int getFlags();

    void setFlags(int flags);

    default void add(NEnum... vals) {
        require(vals);

        int flags = this.getFlags();
        for (NEnum val : vals) {
            flags |= val.getValue();
        }
        this.setFlags(flags);
    }

    default void remove(NEnum... vals) {
        require(vals);

        int flags = this.getFlags();
        for (NEnum val : vals) {
            flags &= ~val.getValue();
        }
        this.setFlags(flags);
    }

    default boolean has(NEnum... vals) {
        require(vals);

        int v = 0;
        for (NEnum val : vals) {
            v |= val.getValue();
        }
        return (this.getFlags() & v) == v;
    }

    default <T extends NEnum> List<T> toEnums() {
        List<T> result = new ArrayList<>();
        Class type = this.getClass();
        for (Object o : type.getEnumConstants()) {
            NEnum e = As(o, NEnum.class);
            if (this.has(e)) {
                result.add((T) e);
            }
        }
        return result;
    }

    default String toStrings() {
        return String.join(", ", toEnums().stream().map(p -> p.toString()).collect(Collectors.toList()));
    }

    default void fromStrings(String strings) {
        require(strings);

        Class type = this.getClass();
        for (String n : split(strings, ", ")) {
            NEnum e = (NEnum) Enum.valueOf(type, n);
            add(e);
        }
    }
}
