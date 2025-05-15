package org.rx.io;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.h2.Driver;
import org.h2.api.H2Type;
import org.h2.jdbc.JdbcResultSet;
import org.h2.jdbc.JdbcSQLSyntaxErrorException;
import org.h2.jdbcx.JdbcConnectionPool;
import org.rx.annotation.DbColumn;
import org.rx.bean.*;
import org.rx.core.Arrays;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.*;
import java.util.AbstractMap;
import java.util.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class EntityDatabaseImpl extends Disposable implements EntityDatabase {
    @RequiredArgsConstructor
    static class SqlMeta {
        @Getter
        final Map.Entry<String, Tuple<Field, DbColumn>> primaryKey;
        @Getter
        final Map<String, Tuple<Field, DbColumn>> columns;
        final Map<String, Tuple<String, Tuple<Field, DbColumn>>> jdbcColumns = new HashMap<>();
        final Linq<Map.Entry<String, Tuple<Field, DbColumn>>> insertView;
        final Linq<Map.Entry<String, Tuple<Field, DbColumn>>> secondaryView;
        final String insertSql;
        final String updateSql;
        final String deleteSql;
        final String selectSql;

        public SqlMeta(String primaryKey, Map<String, Tuple<Field, DbColumn>> columns
                , String insertSql, String updateSql, String deleteSql, String selectSql) {
            this.primaryKey = new AbstractMap.SimpleEntry<>(primaryKey, columns.get(primaryKey));
            this.columns = columns;
            for (Map.Entry<String, Tuple<Field, DbColumn>> entry : columns.entrySet()) {
                jdbcColumns.put(entry.getKey().toUpperCase(), Tuple.of(entry.getKey(), entry.getValue()));
            }
            insertView = Linq.from(columns.entrySet()).where(p -> p.getValue().right == null || !p.getValue().right.autoIncrement());
            secondaryView = Linq.from(columns.entrySet()).where(p -> !eq(p.getKey(), getPrimaryKey().getKey()));

            this.insertSql = insertSql;
            this.updateSql = updateSql;
            this.deleteSql = deleteSql;
            this.selectSql = selectSql;
        }

        public Tuple<String, Tuple<Field, DbColumn>> getJdbcColumn(String col) {
            Tuple<String, Tuple<Field, DbColumn>> bi = jdbcColumns.get(col);
            if (bi == null) {
                for (Map.Entry<String, Tuple<String, Tuple<Field, DbColumn>>> entry : jdbcColumns.entrySet()) {
                    if (entry.getKey().replace("_", "").equals(col)) {
                        jdbcColumns.put(col, bi = entry.getValue());
                        break;
                    }
                }
            }
            return bi;
        }
    }

    static final String SQL_CREATE = "CREATE TABLE IF NOT EXISTS $TABLE\n" +
            "(\n" +
            "$CREATE_COLUMNS" +
            "\tconstraint $TABLE_PK\n" +
            "\t\tprimary key ($PK)\n" +
            ");";
    static final String SQL_CREATE_TEMP_TABLE = "CREATE TABLE $TABLE\n" +
            "(\n" +
            "$CREATE_COLUMNS" +
            ");";
    static final String $TABLE = "$TABLE", $CREATE_COLUMNS = "$CREATE_COLUMNS", $PK = "$PK",
            $UPDATE_COLUMNS = "$UPDATE_COLUMNS";
    static final Map<Class<?>, H2Type> H2_TYPES = new ConcurrentHashMap<>();
    static final Serializer SERIALIZER = Serializer.DEFAULT;
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
        H2_TYPES.put(Timestamp.class, H2Type.TIMESTAMP);
        H2_TYPES.put(UUID.class, H2Type.UUID);

        H2_TYPES.put(Reader.class, H2Type.CLOB);
        H2_TYPES.put(InputStream.class, H2Type.BLOB);

        Driver driver = Driver.load();
        log.info("Load H2 driver {}.{}", driver.getMajorVersion(), driver.getMinorVersion());
    }

    static String columnName(Field field, DbColumn dbColumn, BiFunc<String, String> columnMapping) {
        if (dbColumn != null && !dbColumn.name().isEmpty()) {
            return dbColumn.name();
        }
        return columnMapping != null ? columnMapping.apply(field.getName())
                : field.getName();
    }

    static String toH2Type(Class<?> fieldType) {
        String h2Type;
        if (Reflects.isAssignable(fieldType, NEnum.class, false)) {
            h2Type = H2Type.INTEGER.getName();
        } else if (Reflects.isAssignable(fieldType, Decimal.class, false) || fieldType == BigDecimal.class) {
//            h2Type = H2Type.NUMERIC.getName();
            h2Type = "NUMERIC(56, 6)";
        } else if (fieldType.isArray()) {
//            if (fieldType.getComponentType() == Object.class) {
            h2Type = H2Type.BLOB.getName();
//            } else {
//                h2Type = H2Type.JAVA_OBJECT.getName();
//            }
        } else {
            h2Type = H2_TYPES.getOrDefault(Reflects.primitiveToWrapper(fieldType), H2Type.JAVA_OBJECT).getName();
        }
        return h2Type;
    }

    @SneakyThrows
    static void fillParams(PreparedStatement stmt, List<Object> params) {
        for (int i = 0; i < params.size(); ) {
            Object val = params.get(i++);
            if (val != null) {
                if (val instanceof NEnum) {
                    stmt.setInt(i, ((NEnum<?>) val).getValue());
                    continue;
                }
                if (val.getClass().isArray()) {
                    try (IOStream stream = SERIALIZER.serialize(val)) {
                        stmt.setBinaryStream(i, stream.getReader());
                    }
                    continue;
                }
                if (val instanceof Decimal) {
                    stmt.setBigDecimal(i, ((Decimal) val).getValue());
                    continue;
                }
            }
            stmt.setObject(i, val);
        }
    }

    @SneakyThrows
    static Object convertCell(Class<?> type, Object cell) {
        if (cell == null) {
            return Reflects.defaultValue(type);
        }
        if (type.isArray()) {
//            if (type.getComponentType() == Object.class) {
//                Object[] arr;
//                Blob blob = (Blob) cell;
//                if (blob.length() == 0) {
//                    arr = Arrays.EMPTY_OBJECT_ARRAY;
//                } else {
//                    arr = SERIALIZER.deserialize(IOStream.wrap(null, blob.getBinaryStream()));
//                }
//                return arr;
//            }
            Blob blob = (Blob) cell;
            if (blob.length() == 0) {
                return null;
            }
            return SERIALIZER.deserialize(IOStream.wrap(null, blob.getBinaryStream()));
        }
        return Reflects.changeType(cell, type);
    }

    final String filePath;
    final String timeRollingPattern;
    @Setter
    int rollingHours = 48;
    final int maxConnections;
    final Set<Class<?>> mappedEntityTypes = ConcurrentHashMap.newKeySet();
    @Setter
    BiFunc<String, String> columnMapping = EntityQueryLambda.TO_UNDERSCORE_COLUMN_MAPPING;
    @Setter
    BiFunc<Class<?>, String> tableMapping = EntityQueryLambda.TO_UNDERSCORE_TABLE_MAPPING;
    @Setter
    boolean autoRollbackOnError;
    @Setter
    int slowSqlElapsed = 200;
    String curFilePath;
    JdbcConnectionPool connPool;

    JdbcConnectionPool getConnectionPool() {
        if (connPool == null) {
            String filePath = getFilePath();
            curFilePath = filePath;
            //http://www.h2database.com/html/commands.html#set_cache_size
            String h2Settings = ifNull(RxConfig.INSTANCE.getDisk().getH2Settings(), "");
            log.info("h2Settings: {}", h2Settings);
            connPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0;MODE=MySQL;", filePath) + h2Settings, null, null);
            connPool.setMaxConnections(maxConnections);
            if (!mappedEntityTypes.isEmpty()) {
                createMapping(Linq.from(mappedEntityTypes).toArray());
            }
        }
        return connPool;
    }

    String getFilePath() {
        return timeRollingPattern != null ? filePath + "_" + DateTime.now().toString(timeRollingPattern) : filePath;
    }

    public EntityDatabaseImpl() {
        this(DEFAULT_FILE_PATH, null);
    }

    public EntityDatabaseImpl(String filePath, String timeRollingPattern) {
        this(filePath, timeRollingPattern, 0);
    }

    public EntityDatabaseImpl(String filePath, String timeRollingPattern, int maxConnections) {
        if (maxConnections <= 0) {
            maxConnections = Math.max(10, Constants.CPU_THREADS);
        }

        this.filePath = filePath;
        this.timeRollingPattern = timeRollingPattern;
        this.maxConnections = maxConnections;

        if (timeRollingPattern != null) {
            Tasks.timer().setTimeout(() -> {
                if (connPool == null || Strings.hashEquals(curFilePath, getFilePath())) {
                    return;
                }

                try {
                    clearTimeRollingFiles();
                } catch (Exception e) {
                    TraceHandler.INSTANCE.log(e);
                }

                connPool = null;
            }, d -> RxConfig.INSTANCE.getDisk().getEntityDatabaseRollPeriod(), null, Constants.TIMER_PERIOD_FLAG);
        }
    }

    @Override
    protected void freeObjects() {
        if (connPool != null) {
            connPool.dispose();
        }
    }

    public void clearTimeRollingFiles() {
        if (timeRollingPattern == null) {
            throw new InvalidException("Time rolling policy not enabled");
        }

        String p = filePath;
        if (p.startsWith("~/")) {
            p = Sys.USER_HOME + p.substring(1);
        }
        Files.deleteBefore(Files.getFullPath(p), DateTime.valueOf(DateTime.now().toString(timeRollingPattern), timeRollingPattern).addHours(-rollingHours), "*.mv.db");
    }

    //region CRUD
    @Override
    @SneakyThrows
    public <T> void save(T entity) {
        Class<?> entityType = entity.getClass();
        SqlMeta meta = getMeta(entityType);
        Serializable id = (Serializable) meta.primaryKey.getValue().left.get(entity);
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

    @Override
    @SneakyThrows
    public <T> void save(T entity, boolean doInsert) {
        SqlMeta meta = getMeta(entity.getClass());

        try {
            List<Object> params = new ArrayList<>();
            if (doInsert) {
                for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.insertView) {
                    params.add(col.getValue().left.get(entity));
                }
                executeUpdate(meta.insertSql, params);
                return;
            }

            StringBuilder cols = new StringBuilder(128);
            for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.secondaryView) {
                Object val = col.getValue().left.get(entity);
                if (val == null) {
                    continue;
                }

                cols.appendFormat("`%s`=?,", col.getKey());
                params.add(val);
            }
            cols.setLength(cols.length() - 1);
            Object id = meta.primaryKey.getValue().left.get(entity);
            params.add(id);
            executeUpdate(new StringBuilder(meta.updateSql).replace($UPDATE_COLUMNS, cols.toString()).toString(), params);
        } catch (Exception e) {
            if (e instanceof JdbcSQLSyntaxErrorException && (Strings.startsWith(e.getMessage(), "Column count does not match")
                    || Strings.containsAll(e.getMessage(), "Column", "not found"))) {
                dropMapping(entity.getClass());
                log.info("recreate {} -> {}", entity.getClass(), e.getMessage());
                createMapping(entity.getClass());
                save(entity, doInsert);
                return;
            }
            throw e;
        }
    }

    @Override
    public <T> boolean deleteById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        List<Object> params = new ArrayList<>(1);
        params.add(id);
        return executeUpdate(meta.deleteSql, params) > 0;
    }

    @Override
    public <T> long delete(EntityQueryLambda<T> query) {
        if (query.conditions.isEmpty()) {
            throw new InvalidException("Forbid: empty condition");
        }
        query.limit(1000);
        SqlMeta meta = getMeta(query.entityType);
        query.setColumnMapping(columnMapping);

        StringBuilder sql = new StringBuilder(meta.deleteSql);
        sql.setLength(sql.length() - 2);

        StringBuilder subSql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(subSql, meta.primaryKey.getKey());
        List<Object> params = new ArrayList<>();
        appendClause(subSql, query, params);

        sql.appendFormat(" IN(%s)", subSql);
        String execSql = sql.toString();

        long total = 0;
        int rf;
        while ((rf = executeUpdate(execSql, params)) > 0) {
            total += rf;
        }
        return total;
    }

    @Override
    public <T> long count(EntityQueryLambda<T> query) {
        List<Tuple<BiFunc<T, ?>, EntityQueryLambda.Order>> tmpOrders = null;
        if (!query.orders.isEmpty()) {
            tmpOrders = new ArrayList<>(query.orders);
            query.orders.clear();
        }
        //with limit, the result always 0
        Integer tmpLimit = null;
        if (query.limit != null) {
            tmpLimit = query.limit;
            query.limit = null;
        }
        SqlMeta meta = getMeta(query.entityType);
        query.setColumnMapping(columnMapping);

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
            if (tmpOrders != null) {
                query.orders.addAll(tmpOrders);
            }
            if (tmpLimit != null) {
                query.limit = tmpLimit;
            }
        }
    }

    @Override
    public <T> boolean exists(EntityQueryLambda<T> query) {
        Integer tmpLimit = null, tmpOffset = null;
        if (query.limit != null) {
            tmpLimit = query.limit;
            tmpOffset = query.offset;
            query.limit = 1;
            query.offset = null;
        }
        SqlMeta meta = getMeta(query.entityType);
        query.setColumnMapping(columnMapping);

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

    @Override
    public <T> boolean existsById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        replaceSelectColumns(sql, "1");
        EntityQueryLambda.pkClaus(sql, meta.primaryKey.getKey());
        sql.append(EntityQueryLambda.LIMIT).append("1");
        List<Object> params = new ArrayList<>(1);
        params.add(id);
        return executeScalar(sql.toString(), params) != null;
    }

    @Override
    public <T> T findById(Class<T> entityType, Serializable id) {
        SqlMeta meta = getMeta(entityType);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        EntityQueryLambda.pkClaus(sql, meta.primaryKey.getKey());
        List<Object> params = new ArrayList<>(1);
        params.add(id);
        List<T> list = executeQuery(sql.toString(), params, entityType);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public <T> T findOne(EntityQueryLambda<T> query) {
        List<T> list = findBy(query);
        if (list.size() > 1) {
            throw new InvalidException("Query yields more than one result");
        }
        return list.get(0);
    }

    @Override
    public <T> List<T> findBy(EntityQueryLambda<T> query) {
        SqlMeta meta = getMeta(query.entityType);
        query.setColumnMapping(columnMapping);

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
            throw new InvalidException("Entity {} mapping not found", entityType);
        }
        return meta;
    }
    //endregion

    @Override
    public void compact() {
        executeUpdate("SHUTDOWN COMPACT");
    }

    public <T> void dropIndex(Class<T> entityType, String fieldName) {
        Field field = Reflects.getFieldMap(entityType).get(fieldName);
        DbColumn dbColumn = field.getAnnotation(DbColumn.class);
        String tableName = tableName(entityType);
        String colName = columnName(field, dbColumn, columnMapping);
        String sql = String.format("DROP INDEX %s ON %s;", indexName(tableName, colName), tableName);
        executeUpdate(sql);
    }

    public <T> void createIndex(Class<T> entityType, String fieldName) {
        Field field = Reflects.getFieldMap(entityType).get(fieldName);
        DbColumn dbColumn = field.getAnnotation(DbColumn.class);
        String tableName = tableName(entityType);
        String colName = columnName(field, dbColumn, columnMapping);
        String index = dbColumn != null && (dbColumn.index() == DbColumn.IndexKind.UNIQUE_INDEX_ASC
                || dbColumn.index() == DbColumn.IndexKind.UNIQUE_INDEX_DESC) ? "UNIQUE " : Strings.EMPTY;
        String desc = dbColumn != null && (dbColumn.index() == DbColumn.IndexKind.INDEX_DESC
                || dbColumn.index() == DbColumn.IndexKind.UNIQUE_INDEX_DESC) ? " DESC" : Strings.EMPTY;
        String sql = String.format("CREATE %sINDEX IF NOT EXISTS %s ON %s (%s%s);", index,
                indexName(tableName, colName), tableName, colName, desc);
        executeUpdate(sql);
    }

    String indexName(String tableName, String columnName) {
        return String.format("%s_%s_index", tableName, columnName);
    }

    @Override
    public <T> void dropMapping(Class<T> entityType) {
        SqlMeta meta = getMeta(entityType);

        StringBuilder sql = new StringBuilder(meta.selectSql);
        sql.replace(0, 13, "DROP TABLE");
        executeUpdate(sql.toString());
        mappedEntityTypes.remove(entityType);
    }

    @Override
    public void createMapping(Class<?>... entityTypes) {
        StringBuilder createCols = new StringBuilder();
        StringBuilder insert = new StringBuilder();
        for (Class<?> entityType : entityTypes) {
            createCols.setLength(0);
            String tableName = tableName(entityType);
            insert.setLength(0).appendFormat("INSERT INTO %s VALUES (", tableName);

            String pkName = null;
            Map<String, Tuple<Field, DbColumn>> columns = new LinkedHashMap<>();
            for (Field field : Reflects.getFieldMap(entityType).values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                String colName = columnName(field, dbColumn, columnMapping);
                Tuple<Field, DbColumn> tuple = Tuple.of(field, dbColumn);
                columns.put(colName, tuple);

                Class<?> fieldType = field.getType();
                String h2Type = toH2Type(fieldType);
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
                createCols.appendLine("\t`%s` %s%s,", colName, h2Type, extra);
                if (dbColumn == null || !dbColumn.autoIncrement()) {
                    insert.append("?,");
                } else {
                    insert.append("null,");
                }
            }
            if (pkName == null) {
                throw new InvalidException("Require a primaryKey mapping");
            }

            insert.setLength(insert.length() - 1).append(")");

            String sql = new StringBuilder(SQL_CREATE).replace($TABLE, tableName)
                    .replace($CREATE_COLUMNS, createCols.toString())
                    .replace($PK, pkName).toString();
            log.debug("createMapping\n{}", sql);
            executeUpdate(sql);

            for (Tuple<Field, DbColumn> value : columns.values()) {
                DbColumn dbColumn = value.right;
                if (dbColumn == null || dbColumn.primaryKey()) {
                    continue;
                }
                if (dbColumn.index() == DbColumn.IndexKind.NONE) {
                    try {
                        dropIndex(entityType, value.left.getName());
                    } catch (Exception e) {
                        log.warn("dropIndex: {}", e.getMessage());
                    }
                    continue;
                }
                try {
                    createIndex(entityType, value.left.getName());
                } catch (Exception e) {
                    log.warn("createIndex: {}", e.getMessage());
                }
            }

            String finalPkName = pkName;
            SQL_META.computeIfAbsent(entityType, k -> new SqlMeta(finalPkName, columns, insert.toString(),
                    String.format("UPDATE %s SET $UPDATE_COLUMNS WHERE %s=?", tableName, finalPkName),
                    String.format("DELETE FROM %s WHERE %s=?", tableName, finalPkName),
                    String.format("SELECT * FROM %s", tableName)));
            mappedEntityTypes.add(entityType);
        }
    }

    @Override
    public String tableName(Class<?> entityType) {
//        String n = Extends.metadata(entityType);
//        if (n != null) {
//            return n;
//        }
        return tableMapping != null ? tableMapping.apply(entityType)
                : entityType.getSimpleName();
    }

    //count - Columns require alias
    @SneakyThrows
    public static DataTable sharding(List<DataTable> queryResults, String querySql) {
        DataTable template = queryResults.get(0);
        int startPos = Strings.indexOfIgnoreCase(querySql, EntityQueryLambda.WHERE), endPos;
        if (startPos != -1) {
            int pos = startPos + EntityQueryLambda.WHERE.length();
            endPos = Strings.indexOfIgnoreCase(querySql, EntityQueryLambda.GROUP_BY, pos);
            if (endPos == -1) {
                endPos = Strings.indexOfIgnoreCase(querySql, EntityQueryLambda.ORDER_BY, pos);
            }
            if (endPos == -1) {
                endPos = Strings.indexOfIgnoreCase(querySql, EntityQueryLambda.LIMIT, pos);
            }
            if (endPos == -1) {
                endPos = querySql.length();
            }
            querySql = new StringBuilder(querySql).delete(startPos, endPos - startPos).toString();
        }
        if (Strings.isBlank(template.getTableName())) {
            String c = " FROM ";
            startPos = Strings.indexOfIgnoreCase(querySql, c);
            if (startPos != -1) {
                startPos += c.length();
                endPos = querySql.indexOf(" ", startPos);
                if (endPos != -1) {
                    template.setTableName(querySql.substring(startPos, endPos));
                } else {
                    template.setTableName(querySql.substring(startPos));
                }
            }
            if (Strings.isBlank(template.getTableName())) {
                throw new InvalidException("Invalid table name");
            }
        }
        for (DataColumn<?> column : template.getColumns()) {
            Tuple<String, String> countMap = column.attr(DataTable.HS_COUNT_MAP);
            if (countMap != null) {
                querySql = Strings.replaceIgnoreCase(querySql, countMap.left, String.format("SUM(%s)", countMap.right));
                if (countMap.left.equalsIgnoreCase("COUNT(*)")) {
                    querySql = Strings.replaceIgnoreCase(querySql, "COUNT(1)", String.format("SUM(%s)", countMap.right));
                }
            }
        }
        log.info("shardingSql: {}", querySql);
        DataRow first;
        try {
            first = IteratorUtils.first(template.getRows());
        } catch (IndexOutOfBoundsException e) {
            return template;
        }
        String tableName = template.getTableName();
        StringBuilder createCols = new StringBuilder();
        StringBuilder insert = new StringBuilder();
        insert.appendFormat("INSERT INTO %s VALUES (", tableName);

        int len = template.getColumns().size();
        List<Class<?>> colTypes = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            DataColumn<Object> column = template.getColumn(i);
            String colName = column.getColumnName();
            startPos = colName.indexOf("(");
            if (startPos != -1) {
                startPos += 1;
                endPos = colName.indexOf(")", startPos);
                if (endPos != -1) {
                    colName = colName.substring(startPos, endPos);
                }
            }

            Class<?> fieldType = column.getDataType();
            if (fieldType == null) {
                fieldType = column.attr(DataTable.HS_COLUMN_TYPE);
                if (fieldType == null) {
                    Object cell = first.get(i);
                    if (cell == null) {
                        fieldType = Object.class;
                    } else {
                        fieldType = cell.getClass();
                    }
                }
            }
            colTypes.add(fieldType);
            createCols.appendLine("\t`%s` %s,", colName, toH2Type(fieldType));
            insert.append("?,");
        }
        createCols.setLength(createCols.length() - System.lineSeparator().length() - 1);
        String insertSql = insert.setLength(insert.length() - 1).append(")").toString();

        String url = "jdbc:h2:mem:";
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            String sql = new StringBuilder(SQL_CREATE_TEMP_TABLE).replace($TABLE, tableName)
                    .replace($CREATE_COLUMNS, createCols.toString()).toString();
            log.debug("createMapping\n{}", sql);
            stmt.executeUpdate(sql);

            try (PreparedStatement prepStmt = conn.prepareStatement(insertSql)) {
                List<Object> params = new ArrayList<>();
                for (DataTable dt : queryResults) {
                    for (DataRow row : dt.getRows()) {
                        params.clear();
                        for (DataColumn<?> col : dt.getColumns()) {
                            params.add(row.get(col));
                        }
                        fillParams(prepStmt, params);
                        prepStmt.addBatch();
                    }
                }
                prepStmt.executeBatch();
            }

            DataTable combined = DataTable.read(stmt.executeQuery(querySql));
            int columnCount = combined.getColumns().size();
            for (int i = 0; i < columnCount; i++) {
                for (DataRow row : combined.getRows()) {
                    row.set(i, convertCell(colTypes.get(i), row.get(i)));
                }
            }
            return combined;
        }
    }

    //region jdbc
    public DataTable executeQuery(String sql) {
        return executeQuery(sql, null);
    }

    @Override
    public <T> DataTable executeQuery(String sql, Class<T> entityType) {
        return invoke(conn -> {
            DataTable dt = DataTable.read((JdbcResultSet) conn.createStatement().executeQuery(sql));
            if (entityType != null) {
                SqlMeta meta = getMeta(entityType);
                for (int i = 0; i < dt.getColumns().size(); i++) {
                    DataColumn<?> column = dt.getColumn(i);
                    Tuple<String, Tuple<Field, DbColumn>> bi = meta.getJdbcColumn(column.getColumnName());
                    if (bi == null) {
                        continue;
                    }
                    column.setColumnName(bi.left);
                    Class<?> type = bi.right.left.getType();
                    column.attr(DataTable.HS_COLUMN_TYPE, type);
                    for (DataRow row : dt.getRows()) {
                        row.set(i, convertCell(type, row.get(i)));
                    }
                }
            }
            return dt;
        }, sql, Collections.emptyList());
    }

    @Override
    public int executeUpdate(String sql) {
        return invoke(conn -> {
            return conn.createStatement().executeUpdate(sql);
        }, sql, Collections.emptyList());
    }

    int executeUpdate(String sql, List<Object> params) {
        return invoke(conn -> {
            PreparedStatement stmt = conn.prepareStatement(sql);
            fillParams(stmt, params);
            return stmt.executeUpdate();
        }, sql, params);
    }

    <T> T executeScalar(String sql, List<Object> params) {
        return invoke(conn -> {
            PreparedStatement stmt = conn.prepareStatement(sql);
            fillParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return (T) rs.getObject(1);
                }
                return null;
            }
        }, sql, params);
    }

    <T> List<T> executeQuery(String sql, List<Object> params, Class<T> entityType) {
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
                        //metaData.getColumnName is capitalized
                        String jdbcCol = metaData.getColumnName(i);
                        Tuple<String, Tuple<Field, DbColumn>> pbi = meta.getJdbcColumn(jdbcCol);
                        Tuple<Field, DbColumn> bi;
                        if (pbi == null || (bi = pbi.right) == null) {
                            throw new InvalidException("Mapping {} not found", jdbcCol);
                        }
                        Class<?> type = bi.left.getType();
                        bi.left.set(t, convertCell(type, rs.getObject(i)));
                    }
                    r.add(t);
                }
            }
        }, sql, params);
        return r;
    }
    //endregion

    //region trans
    @Override
    public boolean isInTransaction() {
        return TX_CONN.isSet();
    }

    @Override
    public void begin() {
        begin(Connection.TRANSACTION_NONE);
    }

    @Override
    @SneakyThrows
    public void begin(int transactionIsolation) {
        Connection conn = TX_CONN.getIfExists();
        if (conn == null) {
            TX_CONN.set(conn = getConnectionPool().getConnection());
        }
        if (transactionIsolation != Connection.TRANSACTION_NONE) {
            conn.setTransactionIsolation(transactionIsolation);
        }
        conn.setAutoCommit(false);
    }

    @Override
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

    @Override
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
    private void invoke(BiAction<Connection> fn, String sql, List<Object> params) {
        Connection conn = TX_CONN.getIfExists();
        boolean isInTx = conn != null;
        if (!isInTx) {
            conn = getConnectionPool().getConnection();
        }
        long startTime = System.nanoTime();
        try {
            fn.invoke(conn);
        } catch (Throwable e) {
            if (isInTx && autoRollbackOnError) {
                rollback();
            }
            throw e;
        } finally {
            postInvoke(sql, params, conn, isInTx, startTime);
        }
    }

    private void postInvoke(String sql, List<Object> params, Connection conn, boolean isInTx, long startTime) throws SQLException {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        if (!isInTx) {
            conn.close();
        }
        if (elapsed > slowSqlElapsed) {
            log.warn("slowSql: {} -> {}ms", sql, elapsed);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("executeQuery {}\n{}", sql, toJsonString(params));
            }
        }
    }

    @SneakyThrows
    private <T> T invoke(BiFunc<Connection, T> fn, String sql, List<Object> params) {
        Connection conn = TX_CONN.getIfExists();
        boolean isInTx = conn != null;
        if (!isInTx) {
            conn = getConnectionPool().getConnection();
        }
        long startTime = System.nanoTime();
        try {
            return fn.invoke(conn);
        } catch (Throwable e) {
            if (isInTx && autoRollbackOnError) {
                rollback();
            }
            throw e;
        } finally {
            postInvoke(sql, params, conn, isInTx, startTime);
        }
    }
    //endregion
}
