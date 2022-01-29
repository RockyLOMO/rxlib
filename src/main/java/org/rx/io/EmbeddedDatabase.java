package org.rx.io;

import com.google.common.base.CaseFormat;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.api.H2Type;
import org.h2.jdbcx.JdbcConnectionPool;
import org.rx.annotation.DbColumn;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.InvalidException;
import org.rx.util.Lazy;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EmbeddedDatabase extends Disposable {
    @RequiredArgsConstructor
    static class SqlMeta {
        @Getter
        final String primaryKey;
        @Getter
        final Map<String, Tuple<Field, DbColumn>> columns;
        final String insertSql;
        final String updateSql;
        final String deleteSql;
        final String selectSql;
        final Lazy<NQuery<Map.Entry<String, Tuple<Field, DbColumn>>>> secondaryView = new Lazy<>(() -> NQuery.of(getColumns().entrySet())
                .where(p -> p.getKey().hashCode() != getPrimaryKey().hashCode()));

        Map.Entry<String, Tuple<Field, DbColumn>> primaryKey() {
            return new AbstractMap.SimpleEntry<>(primaryKey, columns.get(primaryKey));
        }

        Iterable<Map.Entry<String, Tuple<Field, DbColumn>>> secondaryColumns() {
            return secondaryView.getValue();
        }
    }

    static final String SQL_CREATE = "CREATE TABLE IF NOT EXISTS $TABLE\n" +
            "(\n" +
            "$CREATE_COLUMNS" +
            "\tconstraint $TABLE_PK\n" +
            "\t\tprimary key ($PK)\n" +
            ");";
    static final String $TABLE = "$TABLE", $CREATE_COLUMNS = "$CREATE_COLUMNS", $PK = "$PK",
            $UPDATE_COLUMNS = "$UPDATE_COLUMNS", $WHERE_PART = "$WHERE_PART";
    static final Map<Class<?>, H2Type> H2_TYPES = new ConcurrentHashMap<>();
    static final Map<Class<?>, SqlMeta> SQL_META = new ConcurrentHashMap<>();

    static {
        H2_TYPES.put(String.class, H2Type.VARCHAR);
        H2_TYPES.put(byte[].class, H2Type.VARBINARY);

        H2_TYPES.put(Boolean.class, H2Type.BOOLEAN);
        H2_TYPES.put(Byte.class, H2Type.TINYINT);
        H2_TYPES.put(Short.class, H2Type.SMALLINT);
        H2_TYPES.put(Integer.class, H2Type.INTEGER);
        H2_TYPES.put(Long.class, H2Type.BIGINT);
        H2_TYPES.put(BigDecimal.class, H2Type.NUMERIC);
        H2_TYPES.put(Float.class, H2Type.REAL);
        H2_TYPES.put(Double.class, H2Type.DOUBLE_PRECISION);
        H2_TYPES.put(Date.class, H2Type.TIMESTAMP);
        H2_TYPES.put(Object.class, H2Type.JAVA_OBJECT);
        H2_TYPES.put(UUID.class, H2Type.UUID);

        H2_TYPES.put(Reader.class, H2Type.CLOB);
        H2_TYPES.put(InputStream.class, H2Type.BLOB);
    }

    final JdbcConnectionPool connectionPool;

    public EmbeddedDatabase(String filePath) {
        connectionPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s", filePath), null, null);
        connectionPool.setMaxConnections(1);
    }

    @Override
    protected void freeObjects() {
        connectionPool.dispose();
    }

    @SneakyThrows
    public <T> void save(T entity) {
        SqlMeta meta = getMeta(entity.getClass());
        Map.Entry<String, Tuple<Field, DbColumn>> pk = meta.primaryKey();
        Object id = pk.getValue().left.get(entity);
        if (id == null) {
            try (Connection conn = connectionPool.getConnection()) {
                PreparedStatement stmt = conn.prepareStatement(meta.insertSql);
                int c = 0;
                for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.secondaryColumns()) {
                    Object val = col.getValue().left.get(entity);
                    stmt.setObject(c++, val);
                }
                stmt.executeUpdate();
            }
            return;
        }

        List<Object> params = new ArrayList<>();
        StringBuilder cols = new StringBuilder(128);
        for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.secondaryColumns()) {
            Object val = col.getValue().left.get(entity);
            if (val == null) {
                continue;
            }

            cols.append("%s=?,", col.getKey());
            params.add(val);
        }
        executeUpdate(new StringBuilder(meta.updateSql).replace($UPDATE_COLUMNS, cols.toString()).toString(), params);
    }

    public <T> List<T> findBy(EntityQueryLambda<T> query) {
        List<Object> params = new ArrayList<>();
        String sql = query.toString(params);
        return executeQuery(sql, params, query.entityType);
    }

    SqlMeta getMeta(Class<?> entityType) {
        SqlMeta meta = SQL_META.get(entityType);
        if (meta == null) {
            throw new InvalidException("Entity %s mapping not found", entityType);
        }
        return meta;
    }

    public void createMapping(Class<?>... entityTypes) {
        StringBuilder createCols = new StringBuilder();
        StringBuilder insert = new StringBuilder();
        for (Class<?> entityType : entityTypes) {
            createCols.setLength(0);
            String tableName = tableName(entityType);
            insert.setLength(0).append("INSERT INTO %s VALUES (", tableName);

            String pkName = null;
            Map<String, Tuple<Field, DbColumn>> columns = new HashMap<>();
            for (Field field : Reflects.getFieldMap(entityType).values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                String colName = columnName(field, dbColumn);
                columns.put(colName, Tuple.of(field, dbColumn));

                H2Type h2Type = H2_TYPES.getOrDefault(field.getType(), H2Type.VARCHAR);
                String extra = Strings.EMPTY;
                if (dbColumn != null) {
                    if (dbColumn.length() > 0) {
                        extra = "(" + dbColumn.length() + ")";
                    }
                    if (dbColumn.primaryKey()) {
                        pkName = colName;
                    }
                    if (dbColumn.autoIncrement()) {
                        extra += " auto_increment";
                    }
                }
                createCols.appendLine("\t%s %s%s,", colName, h2Type.getName(), extra);
                insert.append("?,");
            }
            if (pkName == null) {
                throw new InvalidException("require a primaryKey mapping");
            }

            createCols.setLength(createCols.length() - 1);
            insert.setLength(insert.length() - 1).append(")");

            String sql = new StringBuilder(SQL_CREATE).replace($TABLE, tableName)
                    .replace($CREATE_COLUMNS, createCols.toString())
                    .replace($PK, pkName).toString();
            log.debug("createMapping\n{}", sql);
            executeUpdate(sql);

            String finalPkName = pkName;
            SQL_META.computeIfAbsent(entityType, k -> new SqlMeta(finalPkName, columns, insert.toString(),
                    String.format("UPDATE %s SET $UPDATE_COLUMNS WHERE %s=?", tableName, finalPkName),
                    String.format("DELETE FROM %s WHERE %s=?", tableName, finalPkName),
                    String.format("SELECT * FROM %s", tableName)));
        }
    }

    String columnName(Field field, DbColumn dbColumn) {
        if (dbColumn != null && !dbColumn.name().isEmpty()) {
            return dbColumn.name();
        }
        return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, field.getName());
    }

    String tableName(Class<?> entityType) {
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, entityType.getSimpleName());
    }

    @SneakyThrows
    int executeUpdate(String sql) {
        try (Connection conn = connectionPool.getConnection()) {
            return conn.createStatement().executeUpdate(sql);
        }
    }

    @SneakyThrows
    int executeUpdate(String sql, List<Object> params) {
        try (Connection conn = connectionPool.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            return stmt.executeUpdate();
        }
    }

    @SneakyThrows
    <T> List<T> executeQuery(String sql, List<Object> params, Class<T> entityType) {
        SqlMeta meta = getMeta(entityType);
        List<T> r = new ArrayList<>();
        try (Connection conn = connectionPool.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData metaData = rs.getMetaData();
            while (rs.next()) {
                T t = entityType.newInstance();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    Tuple<Field, DbColumn> bi = meta.columns.get(metaData.getColumnName(i));
                    bi.left.set(t, Reflects.changeType(rs.getObject(i), bi.left.getType()));
                }
                r.add(t);
            }
        }
        return r;
    }
}
