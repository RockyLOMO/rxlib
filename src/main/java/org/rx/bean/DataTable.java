package org.rx.bean;

import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.expression.Alias;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.aggregate.Aggregate;
import org.h2.expression.aggregate.AggregateType;
import org.h2.jdbc.JdbcConnection;
import org.h2.jdbc.JdbcResultSet;
import org.h2.result.LocalResult;
import org.h2.value.ValueToObjectConverter;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.rx.core.App.fromJson;
import static org.rx.core.Extends.*;

@Slf4j
@SuppressWarnings(Constants.NON_RAW_TYPES)
@NoArgsConstructor
public class DataTable implements Extends {
    private static final long serialVersionUID = -7379386582995440975L;
    public static final String HS_COUNT_MAP = "HS_COUNT_MAP";

    public static DataTable read(ResultSet resultSet) {
        return read(resultSet, false);
    }

    @SneakyThrows
    public static DataTable read(ResultSet resultSet, boolean preferColumnName) {
        DataTable dt = new DataTable();
        try (ResultSet rs = resultSet) {
            ResultSetMetaData metaData = rs.getMetaData();
            dt.setTableName(metaData.getTableName(1));
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                dt.addColumn(preferColumnName ? metaData.getColumnName(i) : metaData.getColumnLabel(i));
            }
            readRows(dt, rs, metaData);
        }
        return dt;
    }

    static void readRows(DataTable dt, ResultSet rs, ResultSetMetaData metaData) throws SQLException {
        int columnCount = metaData.getColumnCount();
        List<Object> buf = new ArrayList<>(columnCount);
        while (rs.next()) {
            buf.clear();
            for (int i = 1; i <= columnCount; i++) {
                buf.add(rs.getObject(i));
            }
            dt.addRow(buf.toArray());
        }
    }

    @SneakyThrows
    public static DataTable read(JdbcResultSet resultSet) {
        DataTable dt = new DataTable();
        try (JdbcResultSet rs = resultSet) {
            LocalResult result = (LocalResult) rs.getResult();
            dt.setTableName(result.getTableName(1));
            Expression[] exprs = Reflects.readField(result, "expressions");
            for (Expression expr : exprs) {
                addColumnName(dt, expr);
            }
            if (rs.getMetaData().getColumnCount() != exprs.length) {
                log.info("XX: {}", dt);
            }

            JdbcConnection conn = Reflects.readField(rs, "conn");
            int columnCount = exprs.length;
            List<Object> buf = new ArrayList<>(columnCount);
            while (rs.next()) {
                buf.clear();
                for (int i = 1; i <= columnCount; i++) {
                    buf.add(ValueToObjectConverter.valueToDefaultObject(rs.getInternal(i), conn, true));
//                    buf.add(rs.getObject(i));
                }
                dt.addRow(buf.toArray());
            }
        }
        return dt;
    }

    static void addColumnName(DataTable dt, Expression expr) {
        if (tryAs(expr, ExpressionColumn.class, p -> dt.addColumns(p.getOriginalColumnName()))
                || tryAs(expr, Aggregate.class, p -> {
            if (p.getAggregateType() == AggregateType.COUNT_ALL
                    || p.getAggregateType() == AggregateType.COUNT) {
                String label = p.toString();
                dt.addColumn(label);
                //todo COUNT with no label
                return;
            }
            Expression subExpr = p.getSubexpression(0);
            addColumnName(dt, subExpr);
        })
                || tryAs(expr, Alias.class, p -> {
            Expression subExpr = p.getNonAliasExpression();
            Aggregate aggregate = as(subExpr, Aggregate.class);
            if (aggregate != null
                    && (aggregate.getAggregateType() == AggregateType.COUNT_ALL
                    || aggregate.getAggregateType() == AggregateType.COUNT)) {
                String label = p.getAlias(null, 0);
                dt.addColumn(label).attr(HS_COUNT_MAP, Tuple.of(subExpr.toString(), label));
                return;
            }
            addColumnName(dt, subExpr);
        })) ;
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

    public FluentIterable<DataRow> getRows() {
        return new FluentIterable<DataRow>() {
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

    public DataRow addRow(DataRow row) {
        if (row.table != this) {
            row = newRow(row.getArray());
        }
        rows.add(row);
        return row;
    }

    public DataRow removeRow(DataRow row) {
        rows.remove(row);
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

    public <T> DataColumn<T> removeColumn(String columnName) {
        int index = getColumn(columnName).ordinal;
        DataColumn column = columns.remove(index);
        for (DataRow row : rows) {
            row.items.remove(index);
        }
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
