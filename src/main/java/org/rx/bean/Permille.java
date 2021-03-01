package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import static org.rx.core.App.require;

@RequiredArgsConstructor
public final class Permille implements Serializable, Comparable<Permille> {
    private static final long serialVersionUID = 6193780163058498447L;
    private static final String PERCENT_SYMBOL = "%", PERMILLE_SYMBOL = "‰";
    private static final BigDecimal PERCENT = new BigDecimal(100), PERMILLE = new BigDecimal(1000);

    public static Permille fromPermilleInt(double val) {
        return new Permille(new BigDecimal(val).divide(PERMILLE, 3, RoundingMode.FLOOR));
    }

    public static Permille fromPercentInt(double val) {
        return new Permille(new BigDecimal(val).divide(PERCENT, 3, RoundingMode.FLOOR));
    }

    public static Permille valueOf(BigDecimal value) {
        return new Permille(value);
    }

    public static Permille valueOf(double doubleValue) {
        return valueOf(String.valueOf(doubleValue));
    }

    public static Permille valueOf(String expr) {
        require(expr);

        BigDecimal d = BigDecimal.ONE;
        if (expr.endsWith(PERCENT_SYMBOL)) {
            d = PERCENT;
        } else if (expr.endsWith(PERMILLE_SYMBOL)) {
            d = PERMILLE;
        }
        if (!d.equals(BigDecimal.ONE)) {
            expr = expr.substring(0, expr.length() - 1);
        }
        return new Permille(new BigDecimal(expr).divide(d, 3, RoundingMode.FLOOR));
    }

    @Getter
    private final BigDecimal value;

    public double doubleValue() {
        return value.doubleValue();
    }

    public Permille add(Permille permille) {
        require(permille);

        return new Permille(value.add(permille.value));
    }

    public Permille subtract(Permille permille) {
        require(permille);

        return new Permille(value.subtract(permille.value));
    }

    public Permille multiply(Permille permille) {
        require(permille);

        return new Permille(value.multiply(permille.value));
    }

    public Permille divide(Permille permille) {
        require(permille);

        return new Permille(value.divide(permille.value, 3, RoundingMode.FLOOR));
    }

    public int toPermilleInt() {
        return value.multiply(PERMILLE).setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    public String toPermilleString() {
        return toPermilleInt() + PERMILLE_SYMBOL;
    }

    public int toPercentInt() {
        return toPercentInt(RoundingMode.FLOOR);
    }

    public int toPercentInt(RoundingMode mode) {
        if (mode == null) {
            mode = RoundingMode.FLOOR;
        }
        return value.multiply(PERCENT).setScale(0, mode).intValueExact();
    }

    public String toPercentString() {
        return value.multiply(PERCENT).setScale(1, RoundingMode.FLOOR).stripTrailingZeros() + PERCENT_SYMBOL;
    }

    @Override
    public int compareTo(Permille o) {
        require(o);

        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Permille permille = (Permille) o;
        return Objects.equals(value, permille.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
