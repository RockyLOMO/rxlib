package org.rx.beans;

import lombok.Getter;
import lombok.SneakyThrows;
import org.rx.common.*;

import java.util.*;

import static org.rx.common.Contract.*;

public interface NEnum<T extends Enum<T> & NEnum<T>> {
    final class FlagsEnum<T extends Enum<T> & NEnum<T>> implements NEnum<T> {
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

            Collection<T> list = NQuery.of(App.split(names, ", "))
                    .join(Arrays.asList(type.getEnumConstants()), (p1, p2) -> p1.equals(p2.name()), (p1, p2) -> p2)
                    .asCollection();
            return valueOf(type, EnumSet.copyOf(list));
        }

        private Class<T> type;
        @Getter
        private int flags;

        @Override
        public int getValue() {
            return getFlags();
        }

        @Override
        public String toDescription() {
            return toDescriptions();
        }

        private FlagsEnum(NEnum<T> nEnum) {
            type = (Class<T>) nEnum.getClass();
            flags = nEnum.getValue();
        }

        @SuppressWarnings(Contract.AllWarnings)
        public FlagsEnum<T> add(T... nEnum) {
            require(nEnum);

            for (T t : nEnum) {
                flags |= t.getValue();
            }
            return this;
        }

        public FlagsEnum<T> add(FlagsEnum<T> flagEnum) {
            require(flagEnum);

            flags |= flagEnum.getFlags();
            return this;
        }

        @SuppressWarnings(Contract.AllWarnings)
        public FlagsEnum<T> remove(T... nEnum) {
            require(nEnum);

            for (T t : nEnum) {
                flags &= ~t.getValue();
            }
            return this;
        }

        public FlagsEnum<T> remove(FlagsEnum<T> flagEnum) {
            require(flagEnum);

            flags &= ~flagEnum.getFlags();
            return this;
        }

        @SuppressWarnings(Contract.AllWarnings)
        public boolean has(T... nEnum) {
            require(nEnum);

            int val = 0;
            for (T t : nEnum) {
                val |= t.getValue();
            }
            return (flags & val) == val;
        }

        public boolean has(FlagsEnum<T> flagEnum) {
            require(flagEnum);

            int val = flagEnum.getFlags();
            return (flags & val) == val;
        }

        public EnumSet<T> toSet() {
            EnumSet<T> set = EnumSet.noneOf(type);
            for (T constant : type.getEnumConstants()) {
                if (has(constant)) {
                    set.add(constant);
                }
            }
            return set;
        }

        public String toNames() {
            return String.join(", ", NQuery.of(toSet()).select(p -> p.name()));
        }

        public String toDescriptions() {
            return String.join(", ", NQuery.of(toSet()).select(p -> p.toDescription()));
        }
    }

    static <T extends Enum<T> & NEnum> T valueOf(Class<T> type, int value) {
        require(type);

        for (T nEnum : type.getEnumConstants()) {
            if (nEnum.getValue() == value) {
                return nEnum;
            }
        }
        return null;
    }

    int getValue();

    @SuppressWarnings(Contract.AllWarnings)
    default FlagsEnum<T> add(T... nEnum) {
        require(nEnum);

        FlagsEnum<T> flagsEnum = new FlagsEnum<>(this);
        flagsEnum.add(nEnum);
        return flagsEnum;
    }

    @SuppressWarnings(Contract.AllWarnings)
    @SneakyThrows
    default String toDescription() {
        Class type = this.getClass();
        return Contract.toDescription(type.getField(((T) this).name()));
    }
}
