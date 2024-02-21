package org.rx.jdbc;

import com.alibaba.druid.pool.DruidDataSource;
import com.mysql.jdbc.MySQLConnection;
import com.mysql.jdbc.StringUtils;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.ProxyConnection;
import lombok.SneakyThrows;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Collections;
import java.util.Map;

import static org.rx.core.Extends.as;

public class JdbcUtil {
    static final String HINT_PREFIX = "/*", HINT_SUFFIX = "*/";
    static final int HINT_PREFIX_AND_SUFFIX_LEN = HINT_PREFIX.length() + HINT_SUFFIX.length();
    static final String HINT_MAP_PAIR = ":", HINT_MAP_DELIMITER = ",";
    static final Linq<String> TRANS_KEYWORDS = Linq.from("COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE");

    public static String appendHeadHint(String sql, Map<String, Object> headHints) {
        return appendHeadHint(sql, Linq.from(headHints.entrySet()).where(p -> p.getValue() != null).toJoinString(HINT_MAP_DELIMITER, p -> {
            String val = p.getValue().toString();
            if (Strings.containsAny(val, HINT_MAP_DELIMITER, HINT_MAP_PAIR)) {
                throw new InvalidException("Value can not contains ',' & ':'");
            }
            return p.getKey() + HINT_MAP_PAIR + val;
        }));
    }

    public static String appendHeadHint(String sql, String headHint) {
        return new StringBuilder(sql.length() + headHint.length() + HINT_PREFIX_AND_SUFFIX_LEN)
                .append(HINT_PREFIX).append(headHint).append(HINT_SUFFIX)
                .append(sql).toString();
    }

    public static Map<String, Object> getHeadHintAsMap(String sql) {
        String hint = getHeadHint(sql);
        if (Strings.isEmpty(hint)) {
            return Collections.emptyMap();
        }
        return Linq.from(Strings.split(hint, HINT_MAP_DELIMITER)).select(p -> {
            String[] pair = new String[2];
            int i = p.indexOf(HINT_MAP_PAIR);
            if (i == -1) {
                pair[0] = p;
                return pair;
            }
            pair[0] = p.substring(0, i);
            pair[1] = p.substring(i + 1);
            return pair;
        }).toMap(p -> p[0], p -> p[1]);
    }

    public static String getHeadHint(String sql) {
        sql = Strings.stripStart(sql, null);
        if (!Strings.startsWith(sql, HINT_PREFIX)) {
            return Strings.EMPTY;
        }
        int e = sql.indexOf(HINT_SUFFIX);
        if (e == -1) {
            return Strings.EMPTY;
        }
        return sql.substring(HINT_PREFIX.length(), e);
    }

    public static SqlStatementType getStatementType(String query) {
        String stripComments = StringUtils.stripComments(query, "'\"", "'\"", true, false, true, true);
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "SELECT")) {
            return SqlStatementType.SELECT;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "UPDATE")) {
            return SqlStatementType.UPDATE;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "INSERT")) {
            return SqlStatementType.INSERT;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "DELETE")) {
            return SqlStatementType.DELETE;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "SET")
                || TRANS_KEYWORDS.any(p -> StringUtils.startsWithIgnoreCaseAndWs(stripComments, p))) {
            return SqlStatementType.SET;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "USE")) {
            return SqlStatementType.USE;
        }
        if (StringUtils.startsWithIgnoreCaseAndWs(stripComments, "SHOW")) {
            return SqlStatementType.SHOW;
        }
        throw new InvalidException("Unknown statement type, {}", query);
//        return SqlStatementType.UPDATE;
    }

    public static String killCommand(Connection conn) {
        ProxyConnection as = as(conn, ProxyConnection.class);
        if (as != null) {
            conn = Reflects.readField(conn, "delegate");
        }
        return killCommand((MySQLConnection) conn);
    }

    @SneakyThrows
    public static String killCommand(MySQLConnection mysqlConn) {
        Long threadId = Reflects.readField(mysqlConn.getIO(), "threadId");
        return "KILL QUERY " + threadId;
    }

    public static DataSourceConfig getDataSourceConfig(DataSource ds) {
        if (ds instanceof HikariDataSource) {
            HikariDataSource hds = (HikariDataSource) ds;
            return new DataSourceConfig(hds.getJdbcUrl(), hds.getUsername(), hds.getPassword());
        }
        if (ds instanceof DruidDataSource) {
            DruidDataSource dhs = ((DruidDataSource) ds);
            return new DataSourceConfig(dhs.getUrl(), dhs.getUsername(), dhs.getPassword());
        }
        if (ds instanceof JdbcExecutor.DefaultDataSource) {
            return ((JdbcExecutor.DefaultDataSource) ds).config;
        }
        throw new UnsupportedOperationException(String.format("DataSource %s not support", ds.getClass()));
    }

    @SneakyThrows
    public static String getSqlTypeName(int sqlType) {
        for (Field field : Types.class.getFields()) {
            if (field.getInt(null) == sqlType) {
                return field.getName();
            }
        }
        return "VARCHAR";
    }

    public static int getSqlType(Object val) {
        if (val == null) {
            return Types.NULL;
        }

        if (val instanceof Boolean) {
            return Types.BOOLEAN;
        } else if (val instanceof Byte) {
            return Types.TINYINT;
        } else if (val instanceof Short) {
            return Types.SMALLINT;
        } else if (val instanceof Integer) {
            return Types.INTEGER;
        } else if (val instanceof Long) {
            return Types.BIGINT;
        } else if (val instanceof Float) {
            return Types.FLOAT;
        } else if (val instanceof Double) {
            return Types.DOUBLE;
        } else if (val instanceof Time) {
            return Types.TIME;
        } else if (val instanceof Timestamp) {
            return Types.TIMESTAMP;
        } else if (val instanceof BigDecimal) {
            return Types.DECIMAL;
        } else if (
//                val instanceof Date ||
                val instanceof java.util.Date) {
            return Types.DATE;
        } else if (val instanceof InputStream) {
            return Types.LONGVARBINARY;
        } else if (val instanceof Reader) {
            return Types.LONGVARCHAR;
        }
        return Types.VARCHAR;
    }

    @SneakyThrows
    public static void print(ResultSet resultSet) {
        try (ResultSet x = resultSet) {
            ResultSetMetaData metaData = x.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                System.out.print(metaData.getColumnLabel(i) + "\t");
            }
            System.out.println();
            while (x.next()) {
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    System.out.print(x.getObject(i) + "\t");
                }
                System.out.println();
            }
        }
    }
}
