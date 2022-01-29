package org.rx.io;

import lombok.RequiredArgsConstructor;
import org.rx.bean.BiTuple;
import org.rx.core.NQuery;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.rx.util.function.BiFunc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class EntityQueryLambda<T> implements Serializable {
    @RequiredArgsConstructor
    enum Operator {
        AND("(%s)AND(%s)"), OR("(%s)OR(%s)"),
        EQ("%s=%s"), NE("%s!=%s"),
        GT("%s>%s"), LT("%s<%s"),
        GE("%s<=%s"), LE("%s>=%s"),
        IN("%s IN(%s)"), NOT_IN("%s NOT IN(%s)"),
        BETWEEN("%s BETWEEN %s AND %s"), NOT_BETWEEN("%s NOT BETWEEN %s AND %s"),
        LIKE("%s LIKE %s"), NOT_LIKE("%s NOT LIKE %s");

        final String format;
    }

    static final String OP_AND = " AND ", DB_NULL = "NULL", PARAM_HOLD = "?";
    final Class<T> entityType;
    final ArrayList<BiTuple<Serializable, Operator, ?>> conditions = new ArrayList<>();

    public EntityQueryLambda<T> newClause() {
        return new EntityQueryLambda<>(entityType);
    }

    public EntityQueryLambda<T> and(EntityQueryLambda<T> lambda) {
        ArrayList<BiTuple<Serializable, Operator, ?>> copy = new ArrayList<>(conditions);
        conditions.clear();
        conditions.add(BiTuple.of(copy, Operator.AND, lambda));
        return this;
    }

    public EntityQueryLambda<T> or(EntityQueryLambda<T> lambda) {
        ArrayList<BiTuple<Serializable, Operator, ?>> copy = new ArrayList<>(conditions);
        conditions.clear();
        conditions.add(BiTuple.of(copy, Operator.OR, lambda));
        return this;
    }

    public <R> EntityQueryLambda<T> eq(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.EQ, val));
        return this;
    }

    public <R> EntityQueryLambda<T> ne(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.NE, val));
        return this;
    }

    public <R> EntityQueryLambda<T> gt(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.GT, val));
        return this;
    }

    public <R> EntityQueryLambda<T> lt(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.LT, val));
        return this;
    }

    public <R> EntityQueryLambda<T> ge(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.GE, val));
        return this;
    }

    public <R> EntityQueryLambda<T> le(BiFunc<T, R> fn, R val) {
        conditions.add(BiTuple.of(fn, Operator.LE, val));
        return this;
    }

    @SafeVarargs
    public final <R> EntityQueryLambda<T> in(BiFunc<T, R> fn, R... vals) {
        conditions.add(BiTuple.of(fn, Operator.IN, vals));
        return this;
    }

    @SafeVarargs
    public final <R> EntityQueryLambda<T> notIn(BiFunc<T, R> fn, R... vals) {
        conditions.add(BiTuple.of(fn, Operator.NOT_IN, vals));
        return this;
    }

    public <R> EntityQueryLambda<T> between(BiFunc<T, R> fn, R start, R end) {
        conditions.add(BiTuple.of(fn, Operator.BETWEEN, new Object[]{start, end}));
        return this;
    }

    public <R> EntityQueryLambda<T> notBetween(BiFunc<T, R> fn, R start, R end) {
        conditions.add(BiTuple.of(fn, Operator.NOT_BETWEEN, new Object[]{start, end}));
        return this;
    }

    public <R> EntityQueryLambda<T> like(BiFunc<T, R> fn, String expr) {
        conditions.add(BiTuple.of(fn, Operator.LIKE, expr));
        return this;
    }

    public <R> EntityQueryLambda<T> notLike(BiFunc<T, R> fn, String expr) {
        conditions.add(BiTuple.of(fn, Operator.NOT_LIKE, expr));
        return this;
    }

    @Override
    public String toString() {
        return resolve(conditions, null);
    }

    public String toString(List<Object> params) {
        return resolve(conditions, params);
    }

    static <T> String resolve(ArrayList<BiTuple<Serializable, Operator, ?>> conditions, List<Object> params) {
        StringBuilder b = new StringBuilder(128);
        boolean isParam = params != null;
        for (BiTuple<Serializable, Operator, ?> condition : conditions) {
            Operator op = condition.middle;
            switch (op) {
                case EQ:
                case NE:
                case GT:
                case LT:
                case GE:
                case LE:
                case LIKE:
                case NOT_LIKE: {
                    String propName = Reflects.resolveProperty((BiFunc<T, ?>) condition.left);
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    String valHold;
                    if (isParam) {
                        params.add(condition.right);
                        valHold = PARAM_HOLD;
                    } else {
                        valHold = toValueString(condition.right);
                    }
                    b.append(op.format, propName, valHold);
                }
                break;
                case IN:
                case NOT_IN: {
                    String propName = Reflects.resolveProperty((BiFunc<T, ?>) condition.left);
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    String valHold;
                    if (isParam) {
                        params.add(condition.right);
                        valHold = PARAM_HOLD;
                    } else {
                        valHold = NQuery.of((Object[]) condition.right).toJoinString(",", EntityQueryLambda::toValueString);
                    }
                    b.append(op.format, propName, valHold);
                }
                break;
                case BETWEEN:
                case NOT_BETWEEN: {
                    String propName = Reflects.resolveProperty((BiFunc<T, ?>) condition.left);
                    Object[] p = (Object[]) condition.right;
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    String valHold0, valHold1;
                    if (isParam) {
                        params.add(p[0]);
                        params.add(p[1]);
                        valHold0 = PARAM_HOLD;
                        valHold1 = PARAM_HOLD;
                    } else {
                        valHold0 = toValueString(p[0]);
                        valHold1 = toValueString(p[1]);
                    }
                    b.append(op.format, propName, valHold0, valHold1);
                }
                break;
                case AND:
                case OR:
                    ArrayList<BiTuple<Serializable, Operator, ?>> l = (ArrayList<BiTuple<Serializable, Operator, ?>>) condition.left;
                    EntityQueryLambda<T> r = (EntityQueryLambda<T>) condition.right;
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    b.append(op.format, resolve(l, params), r.toString(params));
                    break;
            }
        }
        return b.toString();
    }

    static String toValueString(Object val) {
        if (val == null) {
            return DB_NULL;
        }
        if (val instanceof String) {
            return String.format("'%s'", ((String) val).replace("'", "‘"));
        }
        return val.toString();
    }
}
