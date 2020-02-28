package org.rx.jdbc;

import lombok.RequiredArgsConstructor;

import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.function.Function;

@RequiredArgsConstructor
public class SimpleResultSetMetaData extends SuperJdbc implements ResultSetMetaData {
    private final List<String> columnLabels;
    private final Function<Integer, Object> getRow;
    private final Function<Integer, String> getColumnTable;
    private final Function<Integer, String> getColumnSchema;

    @Override
    public int getColumnCount() {
        return columnLabels.size();
    }

    @Override
    public boolean isAutoIncrement(int column) {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) {
        return true;
    }

    @Override
    public boolean isSearchable(int column) {
        return true;
    }

    @Override
    public boolean isCurrency(int column) {
        return false;
    }

    @Override
    public int isNullable(int column) {
        return getRow.apply(column) == null ? columnNullable : columnNoNulls;
    }

    @Override
    public boolean isSigned(int column) {
        return false;
    }

    @Override
    public int getColumnDisplaySize(int column) {
        Object v = getRow.apply(column);
        if (v instanceof String) {
            return ((String) v).length();
        }
        return 50;
    }

    @Override
    public String getColumnLabel(int column) {
        return columnLabels.get(column - 1);
    }

    @Override
    public String getColumnName(int column) {
        return getColumnLabel(column);
    }

    @Override
    public String getSchemaName(int column) {
        if (getColumnSchema == null) {
            return "";
        }
        return getColumnSchema.apply(column);
    }

    @Override
    public int getPrecision(int column) {
        return 0;
    }

    @Override
    public int getScale(int column) {
        return 0;
    }

    @Override
    public String getTableName(int column) {
        if (getColumnTable == null) {
            return "";
        }
        return getColumnTable.apply(column);
    }

    @Override
    public String getCatalogName(int column) {
        return Catalog;
    }

    @Override
    public int getColumnType(int column) {
        return JdbcUtil.getSqlType(getRow.apply(column));
    }

    @Override
    public String getColumnTypeName(int column) {
        return JdbcUtil.getSqlTypeName(getColumnType(column));
    }

    @Override
    public boolean isReadOnly(int column) {
        return false;
    }

    @Override
    public boolean isWritable(int column) {
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int column) {
        return false;
    }

    @Override
    public String getColumnClassName(int column) {
        Object v = getRow.apply(column);
        return (v == null ? Object.class : v.getClass()).getName();
    }
}
