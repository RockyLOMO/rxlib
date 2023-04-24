package org.rx.io;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.BiTuple;
import org.rx.bean.Tuple;
import org.rx.core.Extends;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.rx.third.guava.CaseFormat;
import org.rx.util.function.BiFunc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RequiredArgsConstructor
public class EntityQueryLambda<T> implements Extends {
    private static final long serialVersionUID = 6543834322640433503L;

    @RequiredArgsConstructor
    enum Operator {
        AND("(%s)AND(%s)"), OR("(%s)OR(%s)"),
        EQ("%s=%s"), NE("%s!=%s"),
        GT("%s>%s"), LT("%s<%s"),
        GE("%s>=%s"), LE("%s<=%s"),
        IN("%s IN(%s)"), NOT_IN("%s NOT IN(%s)"),
        BETWEEN("%s BETWEEN %s AND %s"), NOT_BETWEEN("%s NOT BETWEEN %s AND %s"),
        LIKE("%s LIKE %s"), NOT_LIKE("%s NOT LIKE %s");

        final String format;
    }

    enum Order {
        ASC,
        DESC
    }

    static final String WHERE = " WHERE ", ORDER_BY = " ORDER BY ", GROUP_BY = " GROUP BY ", LIMIT = " LIMIT ",
            OP_AND = " AND ", DB_NULL = "NULL", PARAM_HOLD = "?";
    static final String FUNC_RAND = "RAND()";

    static void pkClaus(StringBuilder sql, String pk) {
        sql.append(WHERE).append(Operator.EQ.format, pk, PARAM_HOLD);
    }

    final Class<T> entityType;
    @Setter
    boolean autoUnderscoreColumnName;
    final ArrayList<BiTuple<Serializable, Operator, ?>> conditions = new ArrayList<>();
    final List<Tuple<BiFunc<T, ?>, Order>> orders = new ArrayList<>();
    boolean orderByRand;
    Integer limit, offset;

    public EntityQueryLambda<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public EntityQueryLambda<T> limit(int offset, int limit) {
        this.offset = offset;
        this.limit = limit;
        return this;
    }

    public EntityQueryLambda<T> newClause() {
        return new EntityQueryLambda<>(entityType);
    }

    public <R> EntityQueryLambda<T> orderBy(BiFunc<T, R> fn) {
        orders.add(Tuple.of(fn, Order.ASC));
        return this;
    }

    public <R> EntityQueryLambda<T> orderByDescending(BiFunc<T, R> fn) {
        orders.add(Tuple.of(fn, Order.DESC));
        return this;
    }

    public EntityQueryLambda<T> orderByRand() {
        orderByRand = true;
        return this;
    }

    public EntityQueryLambda<T> and(EntityQueryLambda<T> lambda) {
        ArrayList<BiTuple<Serializable, Operator, ?>> copy = new ArrayList<>(conditions);
        conditions.clear();
        conditions.add(BiTuple.of(copy, Operator.AND, lambda));
        orders.addAll(lambda.orders);
        lambda.orders.clear();
        return this;
    }

    public EntityQueryLambda<T> or(EntityQueryLambda<T> lambda) {
        ArrayList<BiTuple<Serializable, Operator, ?>> copy = new ArrayList<>(conditions);
        conditions.clear();
        conditions.add(BiTuple.of(copy, Operator.OR, lambda));
        orders.addAll(lambda.orders);
        lambda.orders.clear();
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
        return toString(null);
    }

    public String toString(List<Object> params) {
        return resolve(conditions, params, orders, orderByRand, autoUnderscoreColumnName, limit, offset);
    }

    static <T> List<T> sharding(List<T> result, EntityQueryLambda<T> lambda) {
        Linq<T> q = Linq.from(result);
        if (!lambda.orders.isEmpty()) {
            boolean isDescFirst = false;
            List<BiFunc<T, ?>> asc = new ArrayList<>(), desc = new ArrayList<>();
            for (int i = 0; i < lambda.orders.size(); i++) {
                Tuple<BiFunc<T, ?>, Order> order = lambda.orders.get(i);
                if (i == 0) {
                    isDescFirst = order.right == Order.DESC;
                }
                if (order.right == Order.DESC) {
                    desc.add(order.left);
                } else {
                    asc.add(order.left);
                }
            }
            if (isDescFirst) {
                q = q.orderByDescendingMany(p -> Linq.from(desc).select(x -> (Object) x.invoke(p)).toList())
                        .orderByMany(p -> Linq.from(asc).select(x -> (Object) x.invoke(p)).toList());
            } else {
                q = q.orderByMany(p -> Linq.from(asc).select(x -> (Object) x.invoke(p)).toList())
                        .orderByDescendingMany(p -> Linq.from(desc).select(x -> (Object) x.invoke(p)).toList());
            }
        }
        if (lambda.offset != null) {
            q = q.skip(lambda.offset);
        }
        if (lambda.limit != null) {
            q = q.take(lambda.limit);
        }
        return q.toList();
    }

    static <T> String resolve(ArrayList<BiTuple<Serializable, Operator, ?>> conditions, List<Object> params,
                              List<Tuple<BiFunc<T, ?>, Order>> orders, boolean orderByRand, boolean autoUnderscoreColumnName,
                              Integer limit, Integer offset) {
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
                    String colName = resolveColumnName(condition.left, autoUnderscoreColumnName);
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
                    b.append(op.format, colName, valHold);
                }
                break;
                case IN:
                case NOT_IN: {
                    String colName = resolveColumnName(condition.left, autoUnderscoreColumnName);
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    String valHold;
                    if (isParam) {
                        params.add(condition.right);
                        valHold = PARAM_HOLD;
                    } else {
                        valHold = Linq.from((Object[]) condition.right).toJoinString(",", EntityQueryLambda::toValueString);
                    }
                    b.append(op.format, colName, valHold);
                }
                break;
                case BETWEEN:
                case NOT_BETWEEN: {
                    String colName = resolveColumnName(condition.left, autoUnderscoreColumnName);
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
                    b.append(op.format, colName, valHold0, valHold1);
                }
                break;
                case AND:
                case OR:
                    ArrayList<BiTuple<Serializable, Operator, ?>> l = (ArrayList<BiTuple<Serializable, Operator, ?>>) condition.left;
                    EntityQueryLambda<T> r = (EntityQueryLambda<T>) condition.right;
                    if (!b.isEmpty()) {
                        b.append(OP_AND);
                    }
                    b.append(op.format, resolve(l, params, null, orderByRand, autoUnderscoreColumnName, limit, offset), r.toString(params));
                    break;
            }
        }

        if (orderByRand) {
            b.append(ORDER_BY).append(FUNC_RAND);
        } else if (!CollectionUtils.isEmpty(orders)) {
            b.append(ORDER_BY);
            for (Tuple<BiFunc<T, ?>, Order> bi : orders) {
                String colName = resolveColumnName(bi.left, autoUnderscoreColumnName);
                b.append("%s %s,", colName, bi.right);
            }
            b.setLength(b.length() - 1);
        }

        if (limit != null) {
            b.append(LIMIT);
            if (offset != null) {
                b.append("%s,", offset);
            }
            b.append(limit);
//            b.append(LIMIT).append(limit);
//            if (offset != null) {
//                b.append(" OFFSET %s", offset);
//            }
        }
        return b.toString();
    }

    static <T> String resolveColumnName(Object fn, boolean autoUnderscoreColumnName) {
        String propName = Reflects.resolveProperty((BiFunc<T, ?>) fn);
        return autoUnderscoreColumnName ? CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, propName)
                : propName;
    }

    static String toValueString(Object val) {
        if (val == null) {
            return DB_NULL;
        }
        if (val instanceof String) {
            return String.format("'%s'", ((String) val).replace("'", "‘"));
        }
        if (val instanceof Date || val instanceof Enum) {
            return String.format("'%s'", val);
        }
        return val.toString();
    }
}
