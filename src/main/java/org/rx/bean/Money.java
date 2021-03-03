package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.rx.core.StringBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import static org.rx.core.App.isNull;

@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Money extends Number implements Comparable<Money> {
    private static final long serialVersionUID = 6538774206304848828L;

    public static final Money ZERO = valueOf(BigDecimal.ZERO);

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

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    public Money(BigDecimal value) {
        this.value = value.setScale(scale, mode);
    }

    protected void before(BigDecimal value) {
    }

    protected Money after(BigDecimal value) {
        return new Money(value, scale, mode);
    }

    public Money add(Money money) {
        if (money == null) {
            money = ZERO;
        }
        return add(money.value);
    }

    public Money add(long value) {
        return add(BigDecimal.valueOf(value));
    }

    public Money add(double value) {
        return add(BigDecimal.valueOf(value));
    }

    public Money add(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.add(val).setScale(scale, mode));
    }

    public Money subtract(Money money) {
        if (money == null) {
            money = Money.ZERO;
        }
        return subtract(money.value);
    }

    public Money subtract(long value) {
        return subtract(BigDecimal.valueOf(value));
    }

    public Money subtract(double value) {
        return subtract(BigDecimal.valueOf(value));
    }

    public Money subtract(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.subtract(val).setScale(scale, mode));
    }

    public Money multiply(Money money) {
        if (money == null) {
            money = Money.ZERO;
        }
        return multiply(money.value);
    }

    public Money multiply(long value) {
        return multiply(BigDecimal.valueOf(value));
    }

    public Money multiply(double value) {
        return multiply(BigDecimal.valueOf(value));
    }

    public Money multiply(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.multiply(val).setScale(scale, mode));
    }

    public Money divide(Money money) {
        if (money == null) {
            money = Money.ZERO;
        }
        return divide(money.value);
    }

    public Money divide(long value) {
        return divide(BigDecimal.valueOf(value));
    }

    public Money divide(double value) {
        return divide(BigDecimal.valueOf(value));
    }

    public Money divide(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.divide(val, scale, mode));
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
