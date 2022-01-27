package org.rx.io;

import com.google.common.base.CaseFormat;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.api.H2Type;
import org.h2.jdbcx.JdbcConnectionPool;
import org.rx.annotation.DbColumn;
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
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EmbeddedDatabase extends Disposable {
    static final String SQL_CREATE = "CREATE TABLE IF NOT EXISTS $table\n" +
            "(\n" +
            "$columns" +
            "\tconstraint $table_PK\n" +
            "\t\tprimary key ($PK)\n" +
            ");";
    static final Map<Class<?>, H2Type> H2_TYPES = new ConcurrentHashMap<>();

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

    public void createMapping(Class<?>... entityTypes) {
        StringBuilder cols = new StringBuilder();
        for (Class<?> entityType : entityTypes) {
            String tableName = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, entityType.getSimpleName());
            String pkName = null;
            cols.setLength(0);
            for (Field field : Reflects.getFieldMap(entityType).values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                Class<?> type = field.getType();
                H2Type h2Type = H2_TYPES.getOrDefault(type, H2Type.VARCHAR);
                String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, field.getName()), extra = Strings.EMPTY;
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                if (dbColumn != null) {
                    if (!Strings.isEmpty(dbColumn.name())) {
                        name = dbColumn.name();
                    }
                    if (dbColumn.length() > 0) {
                        extra = "(" + dbColumn.length() + ")";
                    }
                    if (dbColumn.primaryKey()) {
                        pkName = name;
                    }
                    if (dbColumn.autoIncrement()) {
                        extra += " auto_increment";
                    }
                }
                cols.appendLine("\t%s %s%s,", name, h2Type.getName(), extra);
            }
            cols.setLength(cols.getLength() - 1);
            if (pkName == null) {
                throw new InvalidException("require a primaryKey mapping");
            }
            String sql = new StringBuilder(SQL_CREATE).replace("$table", tableName)
                    .replace("$columns", cols.toString())
                    .replace("$PK", pkName).toString();
            log.debug("createMapping\n{}", sql);
            executeUpdate(sql);
        }
    }

    @SneakyThrows
    int executeUpdate(String sql) {
        try (Connection conn = connectionPool.getConnection()) {
            return conn.createStatement().executeUpdate(sql);
        }
    }
}
