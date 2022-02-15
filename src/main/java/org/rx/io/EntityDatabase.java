package org.rx.io;

import com.google.common.base.CaseFormat;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.api.H2Type;
import org.h2.jdbc.JdbcSQLSyntaxErrorException;
import org.h2.jdbcx.JdbcConnectionPool;
import org.rx.core.Constants;
import org.rx.annotation.DbColumn;
import org.rx.bean.Decimal;
import org.rx.bean.NEnum;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.InvalidException;
import org.rx.util.Lazy;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.toJsonString;
import static org.rx.core.Extends.eq;

@Slf4j
public class EntityDatabase extends Disposable {
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
        final Lazy<NQuery<Map.Entry<String, Tuple<Field, DbColumn>>>> insertView = new Lazy<>(() -> NQuery.of(getColumns().entrySet())
                .where(p -> p.getValue().right == null || !p.getValue().right.autoIncrement()));
        final Lazy<NQuery<Map.Entry<String, Tuple<Field, DbColumn>>>> secondaryView = new Lazy<>(() -> NQuery.of(getColumns().entrySet())
                .where(p -> p.getKey().hashCode() != getPrimaryKey().hashCode()));

        Map.Entry<String, Tuple<Field, DbColumn>> primaryKey() {
            return new AbstractMap.SimpleEntry<>(primaryKey, columns.get(primaryKey));
        }
    }

    public static final Lazy<EntityDatabase> DEFAULT = new Lazy<>(() -> new EntityDatabase("~/RxMeta"));
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
    static final FastThreadLocal<Connection> TX_CONN = new FastThreadLocal<>();

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
    @Setter
    boolean autoUnderscoreColumnName;
    @Setter
    boolean autoRollbackOnError;

    public EntityDatabase(String filePath) {
        this(filePath, Math.max(10, Constants.CPU_THREADS));
    }

    public EntityDatabase(String filePath, int maxConnections) {
        connectionPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s;DB_CLOSE_DELAY=-1", filePath), null, null);
        connectionPool.setMaxConnections(maxConnections);
    }

    @Override
    protected void freeObjects() {
        connectionPool.dispose();
    }

    @SneakyThrows
    public <T> void save(T entity) {
        Class<?> entityType = entity.getClass();
        SqlMeta meta = getMeta(entityType);
        Map.Entry<String, Tuple<Field, DbColumn>> pk = meta.primaryKey();
        Serializable id = (Serializable) pk.getValue().left.get(entity);
        if (id == null) {
            save(entity, true);
            return;
        }

        boolean isInTx = isInTransaction();
        if (!isInTx) {
            begin(Connection.TRANSACTION_READ_COMMITTED);
        }
        try {
            save(entity, !existsById(entityType, id));
            if (!isInTx) {
                commit();
            }
        } catch (Throwable e) {
            if (!isInTx) {
                rollback();
            }
            throw e;
        }
    }

    @SneakyThrows
    public <T> void save(T entity, boolean doInsert) {
        SqlMeta meta = getMeta(entity.getClass());

        List<Object> params = new ArrayList<>();
        if (doInsert) {
            for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.insertView.getValue()) {
                params.add(col.getValue().left.get(entity));
            }
            try {
                executeUpdate(meta.insertSql, params);
            } catch (Exception e) {
                if (e instanceof JdbcSQLSyntaxErrorException && Strings.startsWith(e.getMessage(), "Column count does not match")) {
                    StringBuilder sql = new StringBuilder(meta.selectSql);
                    sql.replace(0, 13, "DROP TABLE");
                    log.info("recreate {}", sql);
                    executeUpdate(sql.toString());
                    createMapping(entity.getClass());
                    save(entity, doInsert);
                    return;
                }
                throw e;
            }
            return;
        }

        StringBuilder cols = new StringBuilder(128);
        for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.secondaryView.getValue()) {
            Object val = col.getValue().left.get(entity);
            if (val == null) {
                continue;
            }

            cols.append("%s=?,", col.getKey());
            params.add(val);
        }
        cols.setLength(cols.length() - 1);
        Object id = meta.primaryKey().getValue().left.get(entity);
        params.add(id);
        executeUpdate(new StringBuilder(meta.updateSql).replace($UPDATE_COLUMNS, cols.toString()).toString(), params);
    }

    public <T> boolean deleteById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        List<Object> params = new ArrayList<>(1);
        params.add(id);
        return executeUpdate(meta.deleteSql, params) > 0;
    }

    public <T> long delete(EntityQueryLambda<T> query) {
        if (query.conditions.isEmpty()) {
            throw new InvalidException("Forbid: empty condition");
        }
        query.limit(1000);
        SqlMeta meta = getMeta(query.entityType);

        StringBuilder sql = new StringBuilder(meta.deleteSql);
        sql.setLength(sql.length() - 2);

        StringBuilder subSql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(subSql, meta.primaryKey);
        List<Object> params = new ArrayList<>();
        appendClause(subSql, query, params);

        sql.append(" IN(%s)", subSql);
        String execSql = sql.toString();

        long total = 0;
        int rf;
        while ((rf = executeUpdate(execSql, params)) > 0) {
            total += rf;
        }
        return total;
    }

    public <T> long count(EntityQueryLambda<T> query) {
        //with limit, the result always 0
        Integer tmpLimit = null;
        if (query.limit != null) {
            tmpLimit = query.limit;
            query.limit = null;
        }
        SqlMeta meta = getMeta(query.entityType);
        query.setAutoUnderscoreColumnName(autoUnderscoreColumnName);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(sql, "COUNT(*)");
        List<Object> params = new ArrayList<>();
        appendClause(sql, query, params);
        try {
            Number num = executeScalar(sql.toString(), params);
            if (num == null) {
                return 0;
            }
            return num.longValue();
        } finally {
            if (tmpLimit != null) {
                query.limit = tmpLimit;
            }
        }
    }

    public <T> boolean exists(EntityQueryLambda<T> query) {
        Integer tmpLimit = null, tmpOffset = null;
        if (query.limit != null) {
            tmpLimit = query.limit;
            tmpOffset = query.offset;
            query.limit = 1;
            query.offset = null;
        }
        SqlMeta meta = getMeta(query.entityType);
        query.setAutoUnderscoreColumnName(autoUnderscoreColumnName);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(sql, "1");
        List<Object> params = new ArrayList<>();
        appendClause(sql, query, params);
        try {
            return executeScalar(sql.toString(), params) != null;
        } finally {
            if (tmpLimit != null) {
                query.limit = tmpLimit;
                query.offset = tmpOffset;
            }
        }
    }

    public <T> boolean existsById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(sql, "1");
        EntityQueryLambda.pkClaus(sql, meta.primaryKey);
        sql.append(EntityQueryLambda.LIMIT).append("1");
        List<Object> params = new ArrayList<>(1);
        params.add(id);
        return executeScalar(sql.toString(), params) != null;
    }

    public <T> T findById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        EntityQueryLambda.pkClaus(sql, meta.primaryKey);
        List<Object> params = new ArrayList<>(1);
        params.add(id);
        List<T> list = executeQuery(sql.toString(), params, entityType);
        return list.isEmpty() ? null : list.get(0);
    }

    public <T> T findOne(EntityQueryLambda<T> query) {
        List<T> list = findBy(query);
        if (list.size() > 1) {
            throw new InvalidException("Query yields more than one result");
        }
        return list.get(0);
    }

    public <T> List<T> findBy(EntityQueryLambda<T> query) {
        SqlMeta meta = getMeta(query.entityType);
        query.setAutoUnderscoreColumnName(autoUnderscoreColumnName);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        List<Object> params = new ArrayList<>();
        appendClause(sql, query, params);
        return executeQuery(sql.toString(), params, query.entityType);
    }

    <T> void replaceSelectColumns(StringBuilder sql, String newColumns) {
        sql.replace(7, 8, newColumns);
    }

    <T> void appendClause(StringBuilder sql, EntityQueryLambda<T> query, List<Object> params) {
        String clause = query.toString(params);
        if (!params.isEmpty()) {
            sql.append(EntityQueryLambda.WHERE);
        }
        sql.append(clause);
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
            Map<String, Tuple<Field, DbColumn>> columns = new LinkedHashMap<>();
            for (Field field : Reflects.getFieldMap(entityType).values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                String colName = columnName(field, dbColumn);
                columns.put(colName, Tuple.of(field, dbColumn));

                H2Type h2Type = H2_TYPES.getOrDefault(Reflects.primitiveToWrapper(field.getType()), H2Type.VARCHAR);
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

//            createCols.setLength(createCols.length() - 1);
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
        return autoUnderscoreColumnName ? CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, field.getName())
                : field.getName().toUpperCase();
    }

    String tableName(Class<?> entityType) {
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, entityType.getSimpleName());
    }

    int executeUpdate(String sql) {
        return invoke(conn -> {
            return conn.createStatement().executeUpdate(sql);
        });
    }

    int executeUpdate(String sql, List<Object> params) {
        if (log.isDebugEnabled()) {
            log.debug("executeUpdate {}\n{}", sql, toJsonString(params));
        }
        return invoke(conn -> {
            PreparedStatement stmt = conn.prepareStatement(sql);
            fillParams(stmt, params);
            return stmt.executeUpdate();
        });
    }

    <T> T executeScalar(String sql, List<Object> params) {
        if (log.isDebugEnabled()) {
            log.debug("executeQuery {}\n{}", sql, toJsonString(params));
        }
        return invoke(conn -> {
            PreparedStatement stmt = conn.prepareStatement(sql);
            fillParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return (T) rs.getObject(1);
                }
                return null;
            }
        });
    }

    <T> List<T> executeQuery(String sql, List<Object> params, Class<T> entityType) {
        if (log.isDebugEnabled()) {
            log.debug("executeQuery {}\n{}", sql, toJsonString(params));
        }
        SqlMeta meta = getMeta(entityType);
        List<T> r = new ArrayList<>();
        invoke(conn -> {
            PreparedStatement stmt = conn.prepareStatement(sql);
            fillParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
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
        });
        return r;
    }

    @SneakyThrows
    void fillParams(PreparedStatement stmt, List<Object> params) {
        for (int i = 0; i < params.size(); ) {
            Object val = params.get(i++);
            if (val instanceof NEnum) {
                stmt.setInt(i, ((NEnum<?>) val).getValue());
                continue;
            }
            if (val instanceof Decimal) {
                stmt.setBigDecimal(i, ((Decimal) val).getValue());
                continue;
            }
            if (val instanceof Enum) {
                stmt.setString(i, ((Enum<?>) val).name());
                continue;
            }
            stmt.setObject(i, val);
        }
    }

    public boolean isInTransaction() {
        return TX_CONN.isSet();
    }

    public void begin() {
        begin(Connection.TRANSACTION_NONE);
    }

    @SneakyThrows
    public void begin(int transactionIsolation) {
        Connection conn = TX_CONN.getIfExists();
        if (conn == null) {
            TX_CONN.set(conn = connectionPool.getConnection());
        }
        if (transactionIsolation != Connection.TRANSACTION_NONE) {
            conn.setTransactionIsolation(transactionIsolation);
        }
        conn.setAutoCommit(false);
    }

    @SneakyThrows
    public void commit() {
        Connection conn = TX_CONN.getIfExists();
        if (conn == null) {
            throw new InvalidException("Not in transaction");
        }
        TX_CONN.remove();
        conn.commit();
        conn.close();
    }

    @SneakyThrows
    public void rollback() {
        Connection conn = TX_CONN.getIfExists();
        if (conn == null) {
//            throw new InvalidException("Not in transaction");
            log.warn("Not in transaction");
            return;
        }
        TX_CONN.remove();
        conn.rollback();
        conn.close();
    }

    @SneakyThrows
    private void invoke(BiAction<Connection> fn) {
        Connection conn = TX_CONN.getIfExists();
        boolean isInTx = conn != null;
        if (!isInTx) {
            conn = connectionPool.getConnection();
        }
        try {
            fn.invoke(conn);
        } catch (Throwable e) {
            if (isInTx && autoRollbackOnError) {
                rollback();
            }
            throw e;
        } finally {
            if (!isInTx) {
                conn.close();
            }
        }
    }

    @SneakyThrows
    private <T> T invoke(BiFunc<Connection, T> fn) {
        Connection conn = TX_CONN.getIfExists();
        boolean isInTx = conn != null;
        if (!isInTx) {
            conn = connectionPool.getConnection();
        }
        try {
            return fn.invoke(conn);
        } catch (Throwable e) {
            if (isInTx && autoRollbackOnError) {
                rollback();
            }
            throw e;
        } finally {
            if (!isInTx) {
                conn.close();
            }
        }
    }
}
