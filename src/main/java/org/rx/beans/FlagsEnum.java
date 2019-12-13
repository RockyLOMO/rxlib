package org.rx.beans;

import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.util.Collection;
import java.util.EnumSet;

import static org.rx.core.Contract.NonWarning;
import static org.rx.core.Contract.require;

public final class FlagsEnum<T extends Enum<T> & NEnum<T>> implements NEnum<T> {
    @SuppressWarnings(NonWarning)
    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(Class<T> type, int flags) {
        require(type);

        FlagsEnum<T> flagsEnum = null;
        for (T constant : type.getEnumConstants()) {
            if ((flags & constant.getValue()) != constant.getValue()) {
                continue;
            }
            if (flagsEnum == null) {
                flagsEnum = new FlagsEnum<>(constant);
                continue;
            }
            flagsEnum.add(constant);
        }
        return flagsEnum;
    }

    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(Class<T> type, EnumSet<T> enumSet) {
        require(type, enumSet);

        int flags = 0;
        for (T t : enumSet) {
            flags |= t.getValue();
        }
        return FlagsEnum.valueOf(type, flags);
    }

    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(Class<T> type, String names) {
        require(type, names);

        Collection<T> list = NQuery.of(Strings.split(names, ", "))
                .join(Arrays.toList(type.getEnumConstants()), (p1, p2) -> p1.equals(p2.name()), (p1, p2) -> p2)
                .asCollection();
        return valueOf(type, EnumSet.copyOf(list));
    }

    private final Class<T> type;
    private int flags;

    public String name() {
        return String.join(", ", NQuery.of(toSet()).select(Enum::name));
    }

    @Override
    public int getValue() {
        return flags;
    }

    @Override
    public String toDescription() {
        return String.join(", ", NQuery.of(toSet()).select(NEnum::toDescription));
    }

    @SuppressWarnings(NonWarning)
    FlagsEnum(NEnum<T> nEnum) {
        type = (Class<T>) nEnum.getClass();
        flags = nEnum.getValue();
    }

    @SuppressWarnings(NonWarning)
    public FlagsEnum<T> add(T... nEnum) {
        require(nEnum);

        for (T t : nEnum) {
            flags |= t.getValue();
        }
        return this;
    }

    @SuppressWarnings(NonWarning)
    public FlagsEnum<T> remove(T... nEnum) {
        require(nEnum);

        for (T t : nEnum) {
            flags &= ~t.getValue();
        }
        return this;
    }

    @SuppressWarnings(NonWarning)
    public boolean has(T... nEnum) {
        require(nEnum);

        int val = 0;
        for (T t : nEnum) {
            val |= t.getValue();
        }
        return (flags & val) == val;
    }

    @SuppressWarnings(NonWarning)
    public EnumSet<T> toSet() {
        EnumSet<T> set = EnumSet.noneOf(type);
        for (T constant : type.getEnumConstants()) {
            if (has(constant)) {
                set.add(constant);
            }
        }
        return set;
    }
}
