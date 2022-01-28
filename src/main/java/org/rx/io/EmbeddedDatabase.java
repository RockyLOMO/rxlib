package org.rx.io;

import com.google.common.base.CaseFormat;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.api.H2Type;
import org.h2.jdbcx.JdbcConnectionPool;
import org.rx.annotation.DbColumn;
import org.rx.bean.BiTuple;
import org.rx.bean.FluentIterable;
import org.rx.core.Disposable;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EmbeddedDatabase extends Disposable {
    @RequiredArgsConstructor
    static class SqlMeta {
        final int pkIndex;
        final List<BiTuple<String, Field, DbColumn>> columns;
        final String insertSql;
        final String updateSql;
        final String deleteSql;
        final String selectSql;

        BiTuple<String, Field, DbColumn> primaryKey() {
            return columns.get(pkIndex);
        }

        Iterable<BiTuple<String, Field, DbColumn>> columnsWithoutPk() {
            return new FluentIterable<BiTuple<String, Field, DbColumn>>() {
                int i;

                @Override
                public boolean hasNext() {
                    return i < columns.size();
                }

                @Override
                public BiTuple<String, Field, DbColumn> next() {
                    if (i == pkIndex) {
                        i++;
                    }
                    return columns.get(i++);
                }
            };
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
    <T> void save(T entity) {
        Class<?> entityType = entity.getClass();
        SqlMeta meta = SQL_META.get(entityType);
        if (meta == null) {
            throw new InvalidException("Entity %s mapping not found", entityType);
        }

        BiTuple<String, Field, DbColumn> pk = meta.primaryKey();
        Object id = pk.middle.get(entity);
        if (id == null) {
            try (Connection conn = connectionPool.getConnection()) {
                PreparedStatement stmt = conn.prepareStatement(meta.insertSql);
                int c = 0;
                for (BiTuple<String, Field, DbColumn> col : meta.columnsWithoutPk()) {
                    Object val = col.middle.get(entity);
                    stmt.setObject(c++, val);
                }
                stmt.executeUpdate();
            }
            return;
        }

        List<Object> params = new ArrayList<>();
        StringBuilder cols = new StringBuilder();
        for (BiTuple<String, Field, DbColumn> col : meta.columnsWithoutPk()) {
            Object val = col.middle.get(entity);
            if (val == null) {
                continue;
            }

            cols.append("%s=?,", col.left);
            params.add(val);
        }
        executeUpdate(new StringBuilder(meta.updateSql).replace($UPDATE_COLUMNS, cols.toString()).toString(), params);
    }


    public void createMapping(Class<?>... entityTypes) {
        StringBuilder createCols = new StringBuilder();
        StringBuilder insert = new StringBuilder();
        for (Class<?> entityType : entityTypes) {
            createCols.setLength(0);
            String tableName = tableName(entityType);
            insert.setLength(0).append("INSERT INTO %s VALUES (", tableName);

            String pkName = null;
            int pkIndex = -1;
            List<BiTuple<String, Field, DbColumn>> columns = new ArrayList<>();
            for (Field field : Reflects.getFieldMap(entityType).values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                String colName = columnName(field, dbColumn);
                columns.add(BiTuple.of(colName, field, dbColumn));

                H2Type h2Type = H2_TYPES.getOrDefault(field.getType(), H2Type.VARCHAR);
                String extra = Strings.EMPTY;
                if (dbColumn != null) {
                    if (dbColumn.length() > 0) {
                        extra = "(" + dbColumn.length() + ")";
                    }
                    if (dbColumn.primaryKey()) {
                        pkIndex = columns.size() - 1;
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

            int finalPkIndex = pkIndex;
            String finalPkName = pkName;
            SQL_META.computeIfAbsent(entityType, k -> new SqlMeta(finalPkIndex, columns, insert.toString(),
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
    <T> T executeQuery(String sql, List<Object> params) {
        try (Connection conn = connectionPool.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            ResultSet rs = stmt.executeQuery();
            return null;
        }
    }
}
