package org.rx.jdbc;

import com.mysql.jdbc.StringUtils;
import lombok.SneakyThrows;
import org.rx.core.CacheKind;
import org.rx.core.MemoryCache;
import org.rx.core.Strings;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.*;
import java.util.regex.Pattern;

public class JdbcUtil {
    public static boolean isQuery(String query) {
        String stripComments = StringUtils.stripComments(query, "'\"", "'\"", true, false, true, true);
        return StringUtils.startsWithIgnoreCaseAndWs(stripComments, "SELECT") || StringUtils.startsWithIgnoreCaseAndWs(stripComments, "SHOW");
    }

    @Deprecated
    public static boolean isQueryByPattern(String query) {
        query = removeQueryComments(query).trim();
        return Strings.startsWithIgnoreCase(query, "SELECT ") ||
                Strings.startsWithIgnoreCase(query, "SHOW ");
    }

    private static String removeQueryComments(String query) {
        return MemoryCache.getOrStore(query, k -> {
            Pattern p = Pattern.compile("(?ms)('(?:''|[^'])*')|--.*?$|/\\*.*?\\*/|#.*?$|");
            return p.matcher(query).replaceAll("$1");
        }, CacheKind.LruCache);
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

    //JDBCType.values()
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
    public static void print(ResultSet x) {
        try (ResultSet d = x) {
            ResultSetMetaData metaData = x.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                System.out.print(metaData.getColumnName(i) + "\t");
            }
            System.out.println();
            while (x.next()) {
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    System.out.print(x.getString(i) + "\t");
                }
                System.out.println();
            }
        }
    }
}
