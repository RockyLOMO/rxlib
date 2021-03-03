package org.rx.bean;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.rx.core.StringBuilder;
import org.rx.core.exception.InvalidException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import static org.rx.core.App.isNull;

@EqualsAndHashCode(callSuper = false)
public class Money extends Number implements Comparable<Money> {
    private static final long serialVersionUID = 6538774206304848828L;

    public static final Money ZERO = new Money() {
        private static final long serialVersionUID = -3787461053639581185L;

        @Override
        public boolean isReadOnly() {
            return true;
        }
    };

    public static Money fromCent(Long cent) {
        return new Money(BigDecimal.valueOf(isNull(cent, 0L) / 100d));
    }

    public static Money valueOf(Double value) {
        return valueOf(BigDecimal.valueOf(value == null ? 0 : value));
    }

    public static Money valueOf(String value) {
        return valueOf(new BigDecimal(value));
    }

    public static Money valueOf(BigDecimal value) {
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        return new Money(value);
    }

    public static Money max(Money a, Money b) {
        return a.value.compareTo(b.value) >= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    @Getter
    private BigDecimal value;
    private int scale = 2;
    private RoundingMode mode = RoundingMode.DOWN;

    public boolean isReadOnly() {
        return scale == -1;
    }

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    public Money() {
        this(BigDecimal.ZERO);
    }

    public Money(BigDecimal value) {
        this.value = value.setScale(scale, mode);
    }

    protected void before(BigDecimal value) {
        if (isReadOnly()) {
            throw new InvalidException("readOnly");
        }
    }

    public Money add(Money money) {
        if (money == null) {
            return this;
        }
        return add(money.value);
    }

    public Money add(long value) {
        return add(BigDecimal.valueOf(value));
    }

    public Money add(double value) {
        return add(BigDecimal.valueOf(value));
    }

    public Money add(BigDecimal augend) {
        if (augend == null) {
            augend = BigDecimal.ZERO;
        }
        before(augend);
        value = value.add(augend).setScale(scale, mode);
        return this;
    }

    public Money subtract(Money money) {
        if (money == null) {
            return this;
        }
        return subtract(money.value);
    }

    public Money subtract(long value) {
        return subtract(BigDecimal.valueOf(value));
    }

    public Money subtract(double value) {
        return subtract(BigDecimal.valueOf(value));
    }

    public Money subtract(BigDecimal subtrahend) {
        if (subtrahend == null) {
            subtrahend = BigDecimal.ZERO;
        }
        before(subtrahend);
        value = value.subtract(subtrahend).setScale(scale, mode);
        return this;
    }

    public Money multiply(Money money) {
        if (money == null) {
            return this;
        }
        return multiply(money.value);
    }

    public Money multiply(long value) {
        return multiply(BigDecimal.valueOf(value));
    }

    public Money multiply(double value) {
        return multiply(BigDecimal.valueOf(value));
    }

    public Money multiply(BigDecimal multiplicand) {
        if (multiplicand == null) {
            value = BigDecimal.ZERO;
            return this;
        }
        before(multiplicand);
        value = value.multiply(multiplicand).setScale(scale, mode);
        return this;
    }

    public Money divide(Money money) {
        if (money == null) {
            return this;
        }
        return divide(money.value);
    }

    public Money divide(long value) {
        return divide(BigDecimal.valueOf(value));
    }

    public Money divide(double value) {
        return divide(BigDecimal.valueOf(value));
    }

    public Money divide(BigDecimal divisor) {
        if (divisor == null) {
            value = BigDecimal.ZERO;
            return this;
        }
        before(divisor);
        value = value.divide(divisor, scale, mode);
        return this;
    }

    public long toCent() {
        return value.multiply(BigDecimal.valueOf(100)).setScale(scale, mode).longValue();//.longValueExact();有毒
    }

    //%.2f
    @Override
    public String toString() {
        return toString(true);
    }

    public String toString(boolean padding) {
        if (padding) {
            return value.toString();
        }
        StringBuilder pattern = new StringBuilder("#");
        for (int i = 0; i < scale; i++) {
            if (i == 0) {
                pattern.append(".");
            }
            pattern.append("#");
        }
        return new DecimalFormat(pattern.toString()).format(value);
    }

    @Override
    public int compareTo(@NotNull Money o) {
        return value.compareTo(o.value);
    }

    @Override
    public int intValue() {
        return value.intValue();
    }

    @Override
    public long longValue() {
        return value.longValue();
    }

    @Override
    public float floatValue() {
        return value.floatValue();
    }

    @Override
    public double doubleValue() {
        return value.doubleValue();
    }
}
