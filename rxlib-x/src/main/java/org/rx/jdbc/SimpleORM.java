package org.rx.jdbc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.annotation.DbColumn;
import org.rx.annotation.Metadata;
import org.rx.bean.$;
import org.rx.bean.Tuple;
import org.rx.core.Cache;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.io.EntityQueryLambda;
import org.rx.third.guava.CaseFormat;
import org.rx.util.function.BiFunc;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.rx.core.Sys.fastCacheKey;

@RequiredArgsConstructor
public class SimpleORM {
    @Getter
    final JdbcExecutor executor;

    static final BiFunc<Class<?>, String> TO_UNDERSCORE_TABLE_MAPPING = t -> {
        Metadata md = t.getAnnotation(Metadata.class);
        if (md != null) {
            return md.value();
        }
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, t.getSimpleName());
    };

    public <T> int insert(T r) {
        Class<?> entityType = r.getClass();
        String sql = JdbcUtil.buildInsertSql(r, TO_UNDERSCORE_TABLE_MAPPING, EntityQueryLambda.TO_UNDERSCORE_COLUMN_MAPPING, null);
        return Sys.callLog(JdbcExecutor.class, entityType.getSimpleName(), new Object[]{sql}, () -> {
            Tuple<Field, DbColumn> t = primaryKey(entityType);
            $<Long> lastInsertId = $.$();
            int re = executor.execute(sql, t.right.autoIncrement()
                    ? Statement.RETURN_GENERATED_KEYS
                    : Statement.NO_GENERATED_KEYS, lastInsertId);
//            log.info("insert {}[{}] -> {}", t.left.getName(), t.right.autoIncrement(), lastInsertId.v);
            if (t.right.autoIncrement()) {
                t.left.set(r, lastInsertId.v);
            }
            return re;
        });
    }

    Tuple<Field, DbColumn> primaryKey(Class<?> entityType) {
        return Cache.getOrSet(fastCacheKey("primaryKey", entityType), k -> {
            Tuple<Field, DbColumn> t = Linq.from(Reflects.getFieldMap(entityType).values()).select(p -> Tuple.of(p, p.getAnnotation(DbColumn.class))).firstOrDefault(p -> {
                DbColumn c = p.right;
                return p.right != null && c.primaryKey();
            });
            if (t == null) {
                throw new InvalidException("Type {} has no primary key", entityType.getName());
            }
            return t;
        });
    }

    @SneakyThrows
    public <T> int updateByPrimaryKey(T r) {
        Class<?> entityType = r.getClass();
        Field primaryKey = primaryKey(entityType).left;
        Object id = primaryKey.get(r);
        if (id == null) {
            throw new InvalidException("Primary key {} is null", primaryKey.getName());
        }
        primaryKey.set(r, null);
        try {
            return updateBy(r, new EntityQueryLambda<T>((Class<T>) entityType) {
                @Override
                public String toString() {
                    return EntityQueryLambda.TO_UNDERSCORE_COLUMN_MAPPING.apply(primaryKey.getName()) + "=" + EntityQueryLambda.toValueString(id);
                }
            });
        } finally {
            primaryKey.set(r, id);
        }
    }

    public <T> int updateBy(T r, EntityQueryLambda<T> q) {
        Class<?> entityType = r.getClass();
        String sql = JdbcUtil.buildUpdateSql(r, q, TO_UNDERSCORE_TABLE_MAPPING, EntityQueryLambda.TO_UNDERSCORE_COLUMN_MAPPING, null);
        return Sys.callLog(JdbcExecutor.class, entityType.getSimpleName(), new Object[]{sql}, () -> executor.execute(sql));
    }

    public <T> T selectFirst(EntityQueryLambda<T> q) {
        return Linq.from(selectList(q.limit(1))).firstOrDefault();
    }

    @SneakyThrows
    public <T> List<T> selectList(EntityQueryLambda<T> q) {
        q.setColumnMapping(EntityQueryLambda.TO_UNDERSCORE_COLUMN_MAPPING);
        Class<?> entityType = Reflects.readField(q, "entityType");
        String sql = new StringBuilder(128)
                .appendMessageFormat("SELECT * FROM {} WHERE ", TO_UNDERSCORE_TABLE_MAPPING.apply(entityType))
                .append(q).toString();
        return Sys.callLog(JdbcExecutor.class, entityType.getSimpleName(), new Object[]{sql}, () -> {
            try (ResultSet rs = executor.executeQuery(sql)) {
                return JdbcUtil.readAs(rs, entityType);
            }
        });
    }
}
