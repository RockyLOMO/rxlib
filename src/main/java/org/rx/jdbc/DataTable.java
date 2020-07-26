package org.rx.jdbc;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.App;
import org.rx.core.InvalidOperationException;
import org.rx.core.NQuery;
import org.rx.core.Reflects;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.rx.core.Contract.eq;

public class DataTable implements Serializable {
    public static DataTable create(String tableName) {
        DataTable dt = new DataTable();
        dt.setTableName(tableName);
        return dt;
    }

    @Getter
    @Setter
    private String tableName;
    @Getter
    private final List<DataColumn> columns = new ArrayList<>();
    @Setter
    private Iterator<DataRow> flowRows;
    private final List<DataRow> memoryRows = new ArrayList<>(0);

    public Iterator<DataRow> getRows() {
        return new Iterator<DataRow>() {
            boolean handleMemory;

            @Override
            public boolean hasNext() {
                if (flowRows != null) {
                    if (flowRows.hasNext()) {
                        return true;
                    }
                }
                if (!handleMemory) {
                    if (CollectionUtils.isEmpty(memoryRows)) {
                        return false;
                    }
                    flowRows = memoryRows.iterator();
                    handleMemory = true;
                    return hasNext();
                }
                return false;
            }

            @Override
            public DataRow next() {
                return flowRows.next();
            }
        };
    }

    public List<DataColumn> createColumns(String... columnNames) {
        return NQuery.of(columnNames).select((p, i) -> {
            DataColumn column = new DataColumn<>(this);
            column.ordinal = i;
            column.columnName = p;
            columns.add(column);
            return column;
        }).toList();
    }

    public <T> DataColumn<T> getColumn(int ordinal) {
        return columns.get(ordinal);
    }

    public <T> DataColumn<T> getColumn(String columnName) {
        return NQuery.of(columns).first(p -> eq(p.columnName, columnName));
    }

    public DataRow createRow(Object... items) {
        DataRow row = new DataRow(this);
        row.setArray(items);
        memoryRows.add(row);
        return row;
    }

    synchronized void setOrdinal(DataColumn column, int ordinal) {
        if (flowRows != null) {
            throw new InvalidOperationException("Not supported");
        }
        if (column.ordinal == ordinal) {
            return;
        }

        columns.remove(ordinal);
        columns.add(ordinal, column);
        for (DataRow row : memoryRows) {
            row.items.add(ordinal, row.items.remove(ordinal));
        }
        column.ordinal = ordinal;
    }

    synchronized <T> DataColumn<T> setDataType(DataColumn column, Class<T> dataType) {
        if (flowRows != null) {
            throw new InvalidOperationException("Not supported");
        }
        if (column.dataType == dataType) {
            return column;
        }

        if (dataType != null) {
            for (DataRow row : memoryRows) {
                row.items.set(column.ordinal, Reflects.changeType(row.items.get(column.ordinal), dataType));
            }
        }
        column.dataType = dataType;
        return column;
    }
}
