package org.rx.bean;

import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.rx.core.App.fromJson;
import static org.rx.core.Extends.eq;

@SuppressWarnings(Constants.NON_RAW_TYPES)
@NoArgsConstructor
public class DataTable implements Extends {
    private static final long serialVersionUID = -7379386582995440975L;

    @SneakyThrows
    public static DataTable read(ResultSet resultSet) {
        DataTable dt = new DataTable();
        try (ResultSet x = resultSet) {
            ResultSetMetaData metaData = x.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                dt.addColumns(metaData.getColumnLabel(i));
            }
            List<Object> buf = new ArrayList<>(columnCount);
            while (x.next()) {
                buf.clear();
                for (int i = 1; i <= columnCount; i++) {
                    buf.add(x.getObject(i));
                }
                dt.addRow(buf.toArray());
            }
        }
        return dt;
    }

    @Getter
    @Setter
    String tableName;
    final List<DataColumn> columns = new ArrayList<>();
    List<DataColumn> readOnlyColumns;
    final List<DataRow> rows = new ArrayList<>();
    @Setter
    Iterator<DataRow> fluentRows;

    public List<DataColumn<?>> getColumns() {
        if (readOnlyColumns == null) {
            readOnlyColumns = Collections.unmodifiableList(columns);
        }
        return (List) readOnlyColumns;
    }

    public Iterator<DataRow> getRows() {
        return new Iterator<DataRow>() {
            Iterator<DataRow> cur = rows.iterator();
            final Iterator<DataRow> next = fluentRows;

            @Override
            public boolean hasNext() {
                if (!cur.hasNext()) {
                    if (cur == next || next == null) {
                        return false;
                    }
                    cur = next;
                    return hasNext();
                }
                return true;
            }

            @Override
            public DataRow next() {
                return cur.next();
            }
        };
    }

    public DataTable(String tableName) {
        this.tableName = tableName;
    }

    public <T> List<T> toList(Class<T> type) {
        List<T> list = new ArrayList<>();
        Iterator<DataRow> rows = getRows();
        while (rows.hasNext()) {
            JSONObject item = new JSONObject(columns.size());
            List<Object> cells = rows.next().items;
            for (int i = 0; i < columns.size(); i++) {
                item.put(columns.get(i).columnName, cells.get(i));
            }
            list.add(fromJson(item, type));
        }
        return list;
    }

    public DataRow addRow(Object... items) {
        DataRow row = newRow(items);
        rows.add(row);
        return row;
    }

    public DataRow newRow(Object... items) {
        DataRow row = new DataRow(this);
        if (!Arrays.isEmpty(items)) {
            row.setArray(items);
        }
        return row;
    }

    public List<DataColumn<?>> addColumns(String... columnNames) {
        List<DataColumn<Object>> columns = NQuery.of(columnNames).select(this::addColumn).toList();
        return (List) columns;
    }

    public <T> DataColumn<T> addColumn(String columnName) {
        DataColumn<T> column = new DataColumn<>(this);
        column.ordinal = columns.size();
        column.columnName = columnName;
        columns.add(column);
        return column;
    }

    public <T> DataColumn<T> getColumn(int ordinal) {
        return columns.get(ordinal);
    }

    public <T> DataColumn<T> getColumn(String columnName) {
        return NQuery.of(columns).first(p -> eq(p.columnName, columnName));
    }

    <T> void setOrdinal(DataColumn<T> column, int ordinal) {
        if (fluentRows != null) {
            throw new InvalidException("Not supported");
        }
        if (column.ordinal == ordinal) {
            return;
        }

        columns.remove(ordinal);
        columns.add(ordinal, column);
        for (DataRow row : rows) {
            row.items.add(ordinal, row.items.remove(ordinal));
        }
        column.ordinal = ordinal;
    }

    <TR> DataColumn<TR> setDataType(DataColumn column, Class<TR> dataType) {
        if (fluentRows != null) {
            throw new InvalidException("Not supported");
        }
        if (Reflects.isAssignable(column.dataType, dataType)) {
            return (DataColumn<TR>) column;
        }

        for (DataRow row : rows) {
            row.items.set(column.ordinal, Reflects.changeType(row.items.get(column.ordinal), dataType));
        }
        column.dataType = dataType;
        return (DataColumn<TR>) column;
    }

    @Override
    public String toString() {
        StringBuilder txt = new StringBuilder();
        for (DataColumn<?> column : getColumns()) {
            txt.append(column.getColumnName()).append("\t");
        }
        txt.appendLine();
        Iterator<DataRow> rows = getRows();
        while (rows.hasNext()) {
            for (Object item : rows.next().items) {
                txt.append(item).append("\t");
            }
            txt.appendLine();
        }
        return txt.toString();
    }
}
