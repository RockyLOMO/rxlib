package org.rx.bean;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.util.function.BiFunc;

import java.util.EnumMap;
import java.util.EnumSet;

import static org.rx.core.App.NON_WARNING;

public final class FlagsEnum<T extends Enum<T> & NEnum<T>> implements NEnum<T> {
    @SuppressWarnings(NON_WARNING)
    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(@NonNull Class<T> type, int flags) {
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

    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(@NonNull Class<T> type, @NonNull EnumSet<T> enumSet) {
        int flags = 0;
        for (T t : enumSet) {
            flags |= t.getValue();
        }
        return FlagsEnum.valueOf(type, flags);
    }

    public static <T extends Enum<T> & NEnum<T>> FlagsEnum<T> valueOf(@NonNull Class<T> type, @NonNull String names) {
        return valueOf(type, EnumSet.copyOf(NQuery.of(Strings.split(names, ", "))
                .join(Arrays.toList(type.getEnumConstants()), (p1, p2) -> p1.equals(p2.name()), (p1, p2) -> p2)
                .toList()));
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
    public String description() {
        return String.join(", ", NQuery.of(toSet()).select(NEnum::description));
    }

    @SuppressWarnings(NON_WARNING)
    FlagsEnum(NEnum<T> nEnum) {
        type = (Class<T>) nEnum.getClass();
        flags = nEnum.getValue();
    }

    @SuppressWarnings(NON_WARNING)
    public FlagsEnum<T> add(@NonNull T... nEnum) {
        for (T t : nEnum) {
            flags |= t.getValue();
        }
        return this;
    }

    @SuppressWarnings(NON_WARNING)
    public FlagsEnum<T> remove(@NonNull T... nEnum) {
        for (T t : nEnum) {
            flags &= ~t.getValue();
        }
        return this;
    }

    @SuppressWarnings(NON_WARNING)
    public boolean has(@NonNull T... nEnum) {
        int val = 0;
        for (T t : nEnum) {
            val |= t.getValue();
        }
        return (flags & val) == val;
    }

    @SuppressWarnings(NON_WARNING)
    public EnumSet<T> toSet() {
        EnumSet<T> set = EnumSet.noneOf(type);
        for (T constant : type.getEnumConstants()) {
            if (has(constant)) {
                set.add(constant);
            }
        }
        return set;
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    public <V> EnumMap<T, V> toMap(@NonNull BiFunc<T, V> compute) {
        EnumMap<T, V> map = new EnumMap<>(type);
        for (T constant : type.getEnumConstants()) {
            if (has(constant)) {
                map.put(constant, compute.invoke(constant));
            }
        }
        return map;
    }
}
