package org.rx.bean;

import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.serializer.JSONSerializer;
import com.alibaba.fastjson.serializer.ObjectSerializer;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.rx.core.StringBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

import static org.rx.core.App.isNull;
import static org.rx.core.App.require;

@JSONType(serializer = Decimal.Serializer.class, deserializer = Decimal.Serializer.class)
@EqualsAndHashCode(callSuper = false)
public class Decimal extends Number implements Comparable<Decimal> {
    public static class Serializer implements ObjectSerializer, ObjectDeserializer {
        @Override
        public <T> T deserialze(DefaultJSONParser parser, Type fieldType, Object fieldName) {
            BigDecimal val = parser.parseObject(BigDecimal.class);
            return (T) Decimal.valueOf(val);
        }

        @Override
        public int getFastMatchToken() {
            return 0;
        }

        @Override
        public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
            serializer.write(((Decimal) object).getValue());
        }
    }

    private static final long serialVersionUID = 6538774206304848828L;
    static final String PERCENT_SYMBOL = "%", PERMILLE_SYMBOL = "‰";
    static final BigDecimal PERCENT = new BigDecimal(100), PERMILLE = new BigDecimal(1000);
    static final int DEFAULT_SCALE = 2;
    static final RoundingMode DEFAULT_MODE = RoundingMode.DOWN;
    public static final Decimal ZERO = valueOf(BigDecimal.ZERO);

    public static Decimal fromPermilleInt(Double value) {
        return fromPermilleInt(BigDecimal.valueOf(value == null ? 0 : value));
    }

    public static Decimal fromPermilleInt(BigDecimal value) {
        return valueOf(value, 3, DEFAULT_MODE).divide(PERMILLE);
    }

    public static Decimal fromPercentInt(Double value) {
        return fromPercentInt(BigDecimal.valueOf(value == null ? 0 : value));
    }

    public static Decimal fromPercentInt(BigDecimal value) {
        return valueOf(value).divide(PERCENT);
    }

    public static Decimal fromCent(Long cent) {
        return valueOf(BigDecimal.valueOf(isNull(cent, 0L))).divide(PERCENT);
    }

    public static Decimal valueOf(String expr) {
        return valueOf(expr, DEFAULT_SCALE, DEFAULT_MODE);
    }

    public static Decimal valueOf(String expr, int scale, RoundingMode mode) {
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
        return valueOf(new BigDecimal(expr), scale, mode).divide(d);
    }

    public static Decimal valueOf(Double value) {
        return valueOf(BigDecimal.valueOf(value == null ? 0 : value));
    }

    public static Decimal valueOf(BigDecimal value) {
        return valueOf(value, DEFAULT_SCALE, DEFAULT_MODE);
    }

    public static Decimal valueOf(BigDecimal value, int scale, RoundingMode mode) {
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        return new Decimal(value, scale, mode);
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    @Getter
    private final BigDecimal value;
    @Setter
    private int scale;
    @Setter
    private RoundingMode mode;

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    public Decimal(BigDecimal value) {
        this(value, DEFAULT_SCALE, DEFAULT_MODE);
    }

    public Decimal(BigDecimal value, int scale, RoundingMode mode) {
        this.value = value.setScale(this.scale = scale, this.mode = mode);
    }

    protected void before(BigDecimal value) {
    }

    protected Decimal after(BigDecimal value) {
        return new Decimal(value, scale, mode);
    }

    public Decimal add(Decimal decimal) {
        if (decimal == null) {
            decimal = ZERO;
        }
        return add(decimal.value);
    }

    public Decimal add(double value) {
        return add(BigDecimal.valueOf(value));
    }

    public Decimal add(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.add(val).setScale(scale, mode));
    }

    public Decimal subtract(Decimal decimal) {
        if (decimal == null) {
            decimal = Decimal.ZERO;
        }
        return subtract(decimal.value);
    }

    public Decimal subtract(double value) {
        return subtract(BigDecimal.valueOf(value));
    }

    public Decimal subtract(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.subtract(val).setScale(scale, mode));
    }

    public Decimal multiply(Decimal decimal) {
        if (decimal == null) {
            decimal = Decimal.ZERO;
        }
        return multiply(decimal.value);
    }

    public Decimal multiply(double value) {
        return multiply(BigDecimal.valueOf(value));
    }

    public Decimal multiply(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.multiply(val).setScale(scale, mode));
    }

    public Decimal divide(Decimal decimal) {
        if (decimal == null) {
            decimal = Decimal.ZERO;
        }
        return divide(decimal.value);
    }

    public Decimal divide(double value) {
        return divide(BigDecimal.valueOf(value));
    }

    public Decimal divide(BigDecimal val) {
        if (val == null) {
            val = BigDecimal.ZERO;
        }
        before(val);
        return after(this.value.divide(val, scale, mode));
    }

    public Decimal max(Decimal o) {
        return this.compareTo(o) >= 0 ? this : o;
    }

    public Decimal min(Decimal o) {
        return this.compareTo(o) <= 0 ? this : o;
    }

    public long toCent() {
        return multiply(PERCENT).value.longValue();//.longValueExact();有毒
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

    public String toCurrencyString() {
        return toCurrencyString(Locale.getDefault());
    }

    public String toCurrencyString(Locale inLocale) {
        return NumberFormat.getCurrencyInstance(inLocale).format(value);
    }

    public int toPermilleInt() {
        return value.multiply(PERMILLE).setScale(0, mode).intValueExact();
    }

    public String toPermilleString() {
        return toPermilleInt() + PERMILLE_SYMBOL;
    }

    public int toPercentInt() {
        return value.multiply(PERCENT).setScale(0, mode).intValueExact();
    }

    public String toPercentString() {
        return value.multiply(PERCENT).setScale(1, mode).stripTrailingZeros().toPlainString() + PERCENT_SYMBOL;
    }

    public int compareTo(double val) {
        return this.compareTo(valueOf(val));
    }

    @Override
    public int compareTo(@NotNull Decimal o) {
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
