package org.rx.bean;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONType;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.writer.ObjectWriter;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.rx.core.StringBuilder;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

import static org.rx.core.Extends.ifNull;

@JSONType(serializer = Decimal.JsonWriter.class, deserializer = Decimal.JsonReader.class)
@EqualsAndHashCode(callSuper = false)
public class Decimal extends Number implements Comparable<Decimal> {
    public static class JsonReader implements ObjectReader<Decimal> {
        @Override
        public Decimal readObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
            if (jsonReader.nextIfNull()) {
                return null;
            }
            return Decimal.valueOf(jsonReader.readBigDecimal());
        }
    }

    public static class JsonWriter implements ObjectWriter<Decimal> {
        @Override
        public void write(JSONWriter jsonWriter, Object object, Object fieldName, Type fieldType, long features) {
            if (object == null) {
                jsonWriter.writeNull();
                return;
            }
            jsonWriter.writeDecimal(((Decimal) object).getValue());
        }
    }

    private static final long serialVersionUID = 6538774206304848828L;
    static final String PERCENT_SYMBOL = "%", PERMILLE_SYMBOL = "‰";
    static final BigDecimal PERCENT_DIVISOR = new BigDecimal(100), PERMILLE_DIVISOR = new BigDecimal(1000);
    static final int DEFAULT_SCALE = 2;
    static final RoundingMode DEFAULT_MODE = RoundingMode.DOWN;
    public static final Decimal ZERO = valueOf(BigDecimal.ZERO);

    public static Decimal fromPermille(Double permille) {
        return fromPermille(BigDecimal.valueOf(permille == null ? 0 : permille));
    }

    public static Decimal fromPermille(BigDecimal permille) {
        return fromPermille(permille, 3);
    }

    public static Decimal fromPermille(BigDecimal permille, int scale) {
        return valueOf(permille, scale, DEFAULT_MODE).divide(PERMILLE_DIVISOR);
    }

    public static Decimal fromPercent(Double percent) {
        return fromPercent(BigDecimal.valueOf(percent == null ? 0 : percent));
    }

    public static Decimal fromPercent(BigDecimal percent) {
        return fromPercent(percent, DEFAULT_SCALE);
    }

    public static Decimal fromPercent(BigDecimal percent, int scale) {
        return valueOf(percent, scale, DEFAULT_MODE).divide(PERCENT_DIVISOR);
    }

    public static Decimal fromCent(Long cent) {
        return valueOf(BigDecimal.valueOf(ifNull(cent, 0L))).divide(PERCENT_DIVISOR);
    }

    public static Decimal valueOf(String expr) {
        return valueOf(expr, DEFAULT_SCALE, DEFAULT_MODE);
    }

    public static Decimal valueOf(String expr, int scale) {
        return valueOf(expr, scale, DEFAULT_MODE);
    }

    public static Decimal valueOf(@NonNull String expr, int scale, RoundingMode mode) {
        BigDecimal d = BigDecimal.ONE;
        if (expr.endsWith(PERCENT_SYMBOL)) {
            d = PERCENT_DIVISOR;
        } else if (expr.endsWith(PERMILLE_SYMBOL)) {
            d = PERMILLE_DIVISOR;
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

    public Decimal(@NonNull BigDecimal value, int scale, RoundingMode mode) {
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
            return Decimal.ZERO;
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
            return Decimal.ZERO;
        }
        before(val);
        return after(this.value.divide(val, scale, mode));
    }

    public Decimal max(double value) {
        return max(Decimal.valueOf(value));
    }

    public Decimal max(Decimal o) {
        return this.compareTo(o) >= 0 ? this : o;
    }

    public Decimal min(double value) {
        return min(Decimal.valueOf(value));
    }

    public Decimal min(Decimal o) {
        return this.compareTo(o) <= 0 ? this : o;
    }

    public boolean gt(double value) {
        return gt(Decimal.valueOf(value));
    }

    public boolean gt(Decimal o) {
        return compareTo(o) > 0;
    }

    public boolean ge(double value) {
        return ge(Decimal.valueOf(value));
    }

    public boolean ge(Decimal o) {
        return compareTo(o) >= 0;
    }

    public boolean lt(double value) {
        return lt(Decimal.valueOf(value));
    }

    public boolean lt(Decimal o) {
        return compareTo(o) < 0;
    }

    public boolean le(double value) {
        return le(Decimal.valueOf(value));
    }

    public boolean le(Decimal o) {
        return compareTo(o) <= 0;
    }

    public boolean eq(double value) {
        return eq(Decimal.valueOf(value));
    }

    public boolean eq(Decimal o) {
        return compareTo(o) == 0;
    }

    public Decimal negate() {
        return after(value.negate());
    }

    public long toCent() {
        //longValueExact() poisonous
        return multiply(PERCENT_DIVISOR).value.longValue();
    }

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
        return toString(pattern.toString());
    }

    //%.2f %02d
    //#,###.000  #.00  ##.00%
    public String toString(String format) {
        return new DecimalFormat(format).format(value);
    }

    public String toCurrencyString() {
        return toCurrencyString(Locale.getDefault());
    }

    public String toCurrencyString(Locale inLocale) {
        return NumberFormat.getCurrencyInstance(inLocale).format(value);
    }

    public int toPermilleInt() {
        return value.multiply(PERMILLE_DIVISOR).setScale(0, mode).intValueExact();
    }

    public String toPermilleString() {
        return toPermilleInt() + PERMILLE_SYMBOL;
    }

    public int toPercentInt() {
        return value.multiply(PERCENT_DIVISOR).setScale(0, mode).intValueExact();
    }

    public String toPercentString() {
        return value.multiply(PERCENT_DIVISOR).setScale(1, mode).stripTrailingZeros().toPlainString() + PERCENT_SYMBOL;
    }

    public int compareTo(double val) {
        return this.compareTo(valueOf(val));
    }

    @Override
    public int compareTo(@NonNull Decimal o) {
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
